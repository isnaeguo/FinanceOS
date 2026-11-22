import Foundation
import Observation
@preconcurrency import FinanceOSShared

/// 与旧 FinanceStore 等价的 JSON/CSV 静态编解码入口（内部走 shared 的 v2 codec）。
enum FinanceDataJsonCodec {
    static func decode(_ content: String) throws -> FinanceDataSnapshot {
        FinanceDataSnapshot(kmp: try KotlinJsonCodec().decode(content: content))
    }

    static func encode(_ snapshot: FinanceDataSnapshot) throws -> String {
        KotlinJsonCodec().encode(snapshot: snapshot.toKMP())
    }
}

enum TransactionCsvCodec {
    static func decode(_ content: String) throws -> [Transaction] {
        try KotlinTransactionCsvCodec(clock: SystemEpochClock()).decode(content: content)
            .map(Transaction.init(kmp:))
    }

    static func encode(_ transactions: [Transaction]) throws -> String {
        KotlinTransactionCsvCodec(clock: SystemEpochClock())
            .encode(transactions: transactions.map { $0.toKMP() })
    }
}

/// 把同步 KMP 调用可能抛出的 Kotlin 异常包装为可展示错误。
extension KotlinThrowable {
    var asDataTransferError: DataTransferError {
        DataTransferError(message: description())
    }
}

extension Error {
    func asFinanceOSError() -> DataTransferError {
        if let transfer = self as? DataTransferError { return transfer }
        return DataTransferError(message: localizedDescription)
    }
}

/// 供 KMP 使用的系统时钟。
final class SystemEpochClock: NSObject, KotlinEpochClock {
    func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

// MARK: - 存储位置

protocol StoreLocationProviding {
    func storeURL() -> URL
}

/// 与旧 DefaultStoreLocation 相同的目录策略：App Group 优先，回退 Application Support。
struct DefaultStoreLocation: StoreLocationProviding {
    static let appGroupIdentifier = "group.com.financeos.ios"

    func storeURL() -> URL {
        if let group = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroupIdentifier) {
            return group.appendingPathComponent("FinanceOS/store.json")
        }
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("FinanceOS", isDirectory: true)
            .appendingPathComponent("store.json")
    }
}

extension Notification.Name {
    static let financeosDataDidChange = Notification.Name("financeos.dataDidChange")
}

// MARK: - 主适配器

/// 时间倒序的内存索引。查询月份只访问对应区间，软删记录仍可按 ID 核对。
struct LedgerIndex: Sendable {
    let records: [Transaction]
    let accounts: [String]
    private let positions: [String: Int]

    init(_ transactions: [Transaction] = []) {
        self.init(ordered: transactions.sorted(by: Self.newestFirst))
    }

    static func replacement(_ records: [Transaction], comparedTo current: [Transaction]) -> LedgerIndex? {
        let sorted = records.sorted(by: Self.newestFirst)
        return sorted == current ? nil : LedgerIndex(ordered: sorted)
    }

    private init(ordered: [Transaction]) {
        records = ordered
        var positions: [String: Int] = [:]
        positions.reserveCapacity(ordered.count)
        var accounts = Set<String>()
        for (position, transaction) in ordered.enumerated() {
            positions[transaction.id] = position
            if !transaction.isDeleted, let account = transaction.accountId {
                accounts.insert(account)
            }
        }
        self.positions = positions
        self.accounts = accounts.sorted()
    }

    subscript(id: String) -> Transaction? {
        positions[id].map { records[$0] }
    }

    func slice(start: Date, end: Date) -> ArraySlice<Transaction> {
        guard start < end else { return records[0..<0] }
        let first = Self.partitionIndex(in: records) { $0.dateTime >= end }
        let last = Self.partitionIndex(in: records, from: first) { $0.dateTime >= start }
        return records[first..<last]
    }

    func setting(_ transaction: Transaction) -> LedgerIndex {
        var ordered = records
        if let position = positions[transaction.id] { ordered.remove(at: position) }
        let insertion = Self.partitionIndex(in: ordered) { Self.newestFirst($0, transaction) }
        ordered.insert(transaction, at: insertion)
        return LedgerIndex(ordered: ordered)
    }

    private static func newestFirst(_ lhs: Transaction, _ rhs: Transaction) -> Bool {
        lhs.dateTime != rhs.dateTime ? lhs.dateTime > rhs.dateTime : lhs.id < rhs.id
    }

    private static func partitionIndex(
        in records: [Transaction], from start: Int = 0, while predicate: (Transaction) -> Bool
    ) -> Int {
        var lower = start
        var upper = records.count
        while lower < upper {
            let middle = lower + (upper - lower) / 2
            if predicate(records[middle]) { lower = middle + 1 }
            else { upper = middle }
        }
        return lower
    }
}

/// SwiftUI 所用的 Room 数据投影。写入按发起顺序提交，最后一次提交后刷新。
@MainActor
@Observable
final class FinanceStoreAdapter {
    private(set) var ledger = LedgerIndex()
    private(set) var ledgerRevision: UInt64 = 0
    private(set) var allCategories: [Category] = []
    private(set) var categories: [Category] = []
    private(set) var categoryLookup: [String: Category] = [:]
    private(set) var allBudgets: [Budget] = []
    private(set) var lastError: String?
    private(set) var loadDate = Date()

    let location: StoreLocationProviding
    let calendar: Calendar

    private(set) var saveTask: Task<Void, Never>?
    private var writeRevision: UInt64 = 0

    private let customDatabasePath: String?
    private let database: FinanceOsDatabase
    private let dataRepository: LocalFinanceDataRepository
    private let transactionRepository: LocalTransactionRepository
    private let budgetRepository: LocalBudgetRepository
    private let clock = SystemEpochClock()

    convenience init(location: StoreLocationProviding = DefaultStoreLocation(), calendar: Calendar = .current) {
        self.init(location: location, databasePath: nil, calendar: calendar)
    }

    /// [databasePath] 供测试注入临时数据库位置；生产使用默认 App Group/Application Support 路径。
    init(location: StoreLocationProviding, databasePath: String?, calendar: Calendar) {
        self.location = location
        self.calendar = calendar
        customDatabasePath = databasePath
        database = AppleDatabaseLocationKt.createAppleFinanceOsDatabase(path: databasePath)
        dataRepository = LocalFinanceDataRepository(database: database)
        transactionRepository = LocalTransactionRepository(dao: database.transactionDao(), clock: clock)
        budgetRepository = LocalBudgetRepository(dao: database.budgetDao(), clock: clock)
        saveTask = Task { [weak self] in
            guard let self else { return }
            await self.migrateLegacyStoreIfNeeded()
            if self.writeRevision == 0 { await self.refreshFromDatabase(revision: 0) }
        }
    }

    // MARK: - 视图可见数据（不含墓碑）

    var transactions: [Transaction] { ledger.records.filter { !$0.isDeleted } }

    var budgets: [Budget] { allBudgets.filter { !$0.isDeleted } }

    var knownAccounts: [String] { ledger.accounts }

    // MARK: - 旧 store.json 迁移

    /// 历史 store.json 可能出现的位置：App Group 启用前的 Application Support 是存量主路径，
    /// 启用后的组容器是本轮新装路径；两者都检查，避免启用组能力后丢数据。
    private func legacyStoreCandidates() -> [URL] {
        if customDatabasePath != nil { return [location.storeURL()] }
        let group = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: DefaultStoreLocation.appGroupIdentifier)?
            .appendingPathComponent("FinanceOS/store.json")
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let support = base.appendingPathComponent("FinanceOS", isDirectory: true)
            .appendingPathComponent("store.json")
        var candidates = [group, support].compactMap { $0 }
        let primary = location.storeURL()
        if !candidates.contains(primary) { candidates.insert(primary, at: 0) }
        return candidates
    }

    private func migrateLegacyStoreIfNeeded() async {
        guard let url = legacyStoreCandidates().first(where: { FileManager.default.fileExists(atPath: $0.path) }) else {
            return
        }
        do {
            let content = try String(contentsOf: url, encoding: .utf8)
            let snapshot = try FinanceDataJsonCodec.decode(content)
            _ = try await dataRepository.merge(snapshot: snapshot.toKMP())
            let migratedURL = url.deletingPathExtension()
                .appendingPathExtension("migrated-\(UUID().uuidString).json")
            try FileManager.default.moveItem(at: url, to: migratedURL)
        } catch {
            lastError = "旧账本迁移未完成，原文件已保留。\(error.localizedDescription)"
        }
    }

    private func refreshFromDatabase(revision: UInt64) async {
        do {
            let (snapshot, index) = try await Self.loadProjection(
                repository: dataRepository, previousRecords: ledger.records
            )
            if revision == writeRevision { apply(snapshot: snapshot, ledger: index) }
        } catch {
            lastError = "无法读取账本。\(error.localizedDescription)"
        }
    }

    nonisolated private static func loadProjection(
        repository: LocalFinanceDataRepository, previousRecords: [Transaction]
    ) async throws -> (FinanceDataSnapshot, LedgerIndex?) {
        let snapshot = FinanceDataSnapshot(kmp: try await repository.snapshot())
        return (snapshot, LedgerIndex.replacement(snapshot.transactions, comparedTo: previousRecords))
    }

    private func apply(snapshot: FinanceDataSnapshot, ledger: LedgerIndex?) {
        if let ledger { setLedger(ledger) }
        setCategories(snapshot.categories)
        allBudgets = snapshot.budgets
        NotificationCenter.default.post(name: .financeosDataDidChange, object: nil)
    }

    private func setLedger(_ index: LedgerIndex) {
        ledger = index
        ledgerRevision &+= 1
    }

    private func setCategories(_ records: [Category]) {
        guard records != allCategories else { return }
        allCategories = records
        categories = records.filter { !$0.isDeleted }.sorted { $0.id < $1.id }
        categoryLookup = Dictionary(categories.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        ledgerRevision &+= 1
    }

    // MARK: - 持久化

    private func enqueueWrite<T>(
        reportError: Bool = true, _ operation: @escaping @MainActor () async throws -> T
    ) -> Task<T, Error> {
        let previous = saveTask
        writeRevision &+= 1
        let revision = writeRevision
        let task = Task {
            await previous?.value
            return try await operation()
        }
        saveTask = Task { [weak self] in
            do { _ = try await task.value }
            catch {
                if reportError { self?.lastError = "账本写入失败。\(error.localizedDescription)" }
            }
            if let self, revision == self.writeRevision {
                await self.refreshFromDatabase(revision: revision)
            }
        }
        return task
    }

    private func persist(_ operation: @escaping @MainActor () async throws -> Void) {
        _ = enqueueWrite(operation)
    }

    private func persistReplaceAll(snapshot: FinanceDataSnapshot) {
        persist { [dataRepository] in
            _ = try await dataRepository.replaceAll(snapshot: snapshot.toKMP())
        }
    }

    private func persistMerge(snapshot: FinanceDataSnapshot) {
        persist { [dataRepository] in
            _ = try await dataRepository.merge(snapshot: snapshot.toKMP())
        }
    }

    private func readSnapshot() async throws -> FinanceDataSnapshot {
        let kmp = try await dataRepository.snapshot()
        return FinanceDataSnapshot(kmp: kmp)
    }

    func clearError() { lastError = nil }

    func scheduleSave() {
        // Room 已负责持久化，保留空实现以兼容旧调用点。
    }

    var storeLocationDescription: String { storeURL().path }

    func storeURL() -> URL {
        if let customDatabasePath { return URL(fileURLWithPath: customDatabasePath) }
        if let path = try? AppleDatabaseLocationKt.defaultAppleFinanceOsDatabasePath() {
            return URL(fileURLWithPath: path)
        }
        return location.storeURL()
    }

    // MARK: - 分类查询

    func category(id: String?) -> Category? {
        guard let id else { return nil }
        return categoryLookup[id]
    }

    func transaction(id: String) -> Transaction? {
        guard let record = ledger[id], !record.isDeleted else { return nil }
        return record
    }

    func containsTransaction(id: String) -> Bool { ledger[id] != nil }

    func applyAiCategory(_ expected: Transaction, categoryId: String) async throws -> Bool {
        let task = enqueueWrite { [transactionRepository] in
            try await transactionRepository.updateCategory(expected: expected.toKMP(), newCategoryId: categoryId)
        }
        return try await task.value.boolValue
    }

    func createAiCategory(_ expected: Transaction, name: String) async throws -> Bool {
        let id = "user-\(UUID().uuidString.lowercased())"
        let task = enqueueWrite(reportError: false) { [transactionRepository] in
            try await transactionRepository.updateCategoryWithNewCategory(
                expected: expected.toKMP(), categoryId: id, name: name
            )
        }
        return try await task.value.boolValue
    }

    func applyWorkspaceRules(_ suggestions: [WorkspaceRuleSuggestion]) async throws -> Int {
        let task = enqueueWrite(reportError: false) { [transactionRepository] in
            var applied = 0
            for entry in suggestions {
                if try await transactionRepository.updateCategory(
                    expected: entry.transaction, newCategoryId: entry.categoryId
                ).boolValue {
                    applied += 1
                }
            }
            return applied
        }
        return try await task.value
    }

    func categories(for type: TransactionType) -> [Category] {
        categories.filter { $0.accepts(type) }
    }

    // MARK: - 流水 CRUD

    func addTransaction(_ transaction: Transaction) {
        var stamped = transaction
        stamped.updatedAt = clock.nowMillis()
        upsertLocally(stamped)
        let kmp = stamped.toKMP()
        persist { [transactionRepository] in
            _ = try await transactionRepository.add(transaction: kmp)
        }
    }

    func updateTransaction(_ transaction: Transaction) {
        var stamped = transaction
        stamped.updatedAt = clock.nowMillis()
        stamped.deletedAt = nil
        upsertLocally(stamped)
        let kmp = stamped.toKMP()
        persist { [transactionRepository] in
            _ = try await transactionRepository.add(transaction: kmp)
        }
    }

    func saveTransaction(_ transaction: Transaction, expected: Transaction?) async throws -> Bool {
        let task = enqueueWrite { [transactionRepository] in
            if let expected {
                return try await transactionRepository.update(
                    expected: expected.toKMP(), replacement: transaction.toKMP()
                ).boolValue
            }
            _ = try await transactionRepository.add(transaction: transaction.toKMP())
            return true
        }
        return try await task.value
    }

    func deleteTransaction(id: String) {
        guard let existing = transaction(id: id) else { return }
        let now = clock.nowMillis()
        var tombstone = existing
        tombstone.deletedAt = now
        tombstone.updatedAt = now
        upsertLocally(tombstone)
        persist { [transactionRepository] in
            _ = try await transactionRepository.delete(id: id)
        }
    }

    private func upsertLocally(_ transaction: Transaction) {
        setLedger(ledger.setting(transaction))
    }

    // MARK: - 分类 CRUD

    @discardableResult
    func addCategory(name: String, type: CategoryType, iconKey: String) -> Category {
        let category = Category(
            id: "user-\(UUID().uuidString.lowercased())",
            name: name.trimmingCharacters(in: .whitespaces),
            type: type,
            iconKey: iconKey,
            isSystem: false,
            updatedAt: clock.nowMillis()
        )
        setCategories(allCategories + [category])
        persistMerge(snapshot: FinanceDataSnapshot(transactions: [], categories: [category], budgets: []))
        return category
    }

    func deleteCategory(id: String) -> Bool {
        guard let category = allCategories.first(where: { $0.id == id }), !category.isSystem else { return false }
        var tombstone = category
        tombstone.deletedAt = clock.nowMillis()
        tombstone.updatedAt = tombstone.deletedAt ?? 0
        setCategories(allCategories.filter { $0.id != id } + [tombstone])
        persistMerge(snapshot: FinanceDataSnapshot(transactions: [], categories: [tombstone], budgets: []))
        return true
    }

    // MARK: - 预算 CRUD

    func setTotalBudget(month: BudgetMonth, amountLimit: Int64) {
        setBudget(month: month, categoryId: nil, amountLimit: amountLimit)
    }

    func setCategoryBudget(month: BudgetMonth, categoryId: String, amountLimit: Int64) {
        setBudget(month: month, categoryId: categoryId, amountLimit: amountLimit)
    }

    func setBudget(month: BudgetMonth, categoryId: String?, amountLimit: Int64) {
        precondition(amountLimit >= 0, "Budget amountLimit must not be negative.")
        let kmpMonth = KotlinBudgetMonth(year: Int32(month.year), month: Int32(month.month))
        if amountLimit == 0 {
            persist { [budgetRepository] in
                _ = try await budgetRepository.delete(month: kmpMonth, categoryId: categoryId)
            }
        } else {
            let budget = Budget(
                id: "budget-\(UUID().uuidString.lowercased())",
                month: month,
                amountLimit: amountLimit,
                categoryId: categoryId
            )
            persist { [budgetRepository] in try await budgetRepository.save(budget: budget.toKMP()) }
        }
    }

    func totalBudget(month: BudgetMonth) -> Budget? {
        budgets.first { $0.month == month && $0.categoryId == nil }
    }

    func categoryBudget(month: BudgetMonth, categoryId: String) -> Budget? {
        budgets.first { $0.month == month && $0.categoryId == categoryId }
    }

    // MARK: - 派生查询（显示层计算，输入为当前可见数据）

    func transactionSlice(in period: MonthPeriod) -> ArraySlice<Transaction> {
        ledger.slice(start: period.startInclusive, end: period.endExclusive)
    }

    func monthlyTransactions(in period: MonthPeriod) -> [Transaction] {
        transactionSlice(in: period).filter { !$0.isDeleted }
    }

    func monthlySummary(in period: MonthPeriod) -> MonthlySummary {
        SummaryCalculations.monthlySummary(transactions: transactionSlice(in: period))
    }

    func budgetStatus(in period: MonthPeriod, summary: MonthlySummary? = nil) -> MonthlyBudgetStatus {
        let monthBudgets = allBudgets.filter { !$0.isDeleted && $0.month == period.month }
        return SummaryCalculations.budgetStatus(summary: summary ?? monthlySummary(in: period), budgets: monthBudgets)
    }

    func dailyAvailableBudget(today: Date = Date(), calendar: Calendar = .current) -> DailyAvailableBudget? {
        let month = BudgetMonth(year: calendar.component(.year, from: today), month: calendar.component(.month, from: today))
        let period = month.period(calendar: calendar)
        guard let budget = totalBudget(month: month) else { return nil }
        let startOfToday = calendar.startOfDay(for: today)
        let day = calendar.component(.day, from: today)
        return SummaryCalculations.dailyAvailableBudget(
            period: period,
            currentDayOfMonth: day,
            startOfToday: startOfToday,
            totalBudget: budget,
            transactions: ledger.slice(start: period.startInclusive, end: startOfToday)
        )
    }

    func loadExpenseTrend(periods: [ExpenseTrendPeriod]) async -> [ExpenseTrendPoint] {
        guard let first = periods.first, let last = periods.last else { return [] }
        let rows = ledger.slice(start: first.startInclusive, end: last.endExclusive)
        let worker = Task.detached(priority: .userInitiated) {
            SummaryCalculations.expenseTrend(periods: periods, transactions: rows)
        }
        return await withTaskCancellationHandler {
            await worker.value
        } onCancel: {
            worker.cancel()
        }
    }

    func monthlyExpenseTrend(anchorMonth: BudgetMonth, count: Int = 6) -> [ExpenseTrendPoint] {
        let periods = SummaryCalculations.monthTrendPeriods(anchorMonth: anchorMonth, count: count, calendar: calendar)
        guard let first = periods.first, let last = periods.last else { return [] }
        let rangeTransactions = ledger.slice(start: first.startInclusive, end: last.endExclusive)
        return SummaryCalculations.expenseTrend(periods: periods, transactions: rangeTransactions)
    }

    func dailyExpenseTrendData(anchorDay: Date = Date(), days: Int) -> [DailyTrendDatum] {
        let periods = SummaryCalculations.dailyTrendPeriods(anchorDay: anchorDay, days: days, calendar: calendar)
        guard let first = periods.first, let last = periods.last else { return [] }
        let rangeTransactions = ledger.slice(start: first.startInclusive, end: last.endExclusive)
        let points = SummaryCalculations.expenseTrend(periods: periods, transactions: rangeTransactions)
        return zip(periods, points).map { DailyTrendDatum(date: $0.startInclusive, amount: $1.amount) }
    }

    func recentTransactions(limit: Int) -> [Transaction] {
        Array(ledger.records.lazy.filter { !$0.isDeleted }.prefix(max(0, limit)))
    }

    // MARK: - 导入 / 导出 / 备份

    func currentSnapshot() -> FinanceDataSnapshot {
        FinanceDataSnapshot(transactions: ledger.records, categories: allCategories, budgets: allBudgets)
    }

    func mergeBackup(_ snapshot: FinanceDataSnapshot) async throws -> FinanceDataImportResult {
        let task = enqueueWrite { [dataRepository] in
            try await dataRepository.merge(snapshot: snapshot.toKMP())
        }
        return FinanceDataImportResult(kmp: try await task.value)
    }

    func replaceBackup(_ snapshot: FinanceDataSnapshot) async throws -> FinanceDataImportResult {
        let task = enqueueWrite { [dataRepository] in
            try await dataRepository.replaceAll(snapshot: snapshot.toKMP())
        }
        return FinanceDataImportResult(kmp: try await task.value)
    }

    func insertStatementTransactions(_ transactions: [Transaction]) async throws -> FinanceDataImportResult {
        let task = enqueueWrite { [dataRepository] in
            try await dataRepository.importTransactions(transactions: transactions.map { $0.toKMP() })
        }
        return FinanceDataImportResult(kmp: try await task.value)
    }

    func exportCommittedJSON() async throws -> String {
        await saveTask?.value
        return try FinanceDataJsonCodec.encode(try await readSnapshot())
    }

    func exportCommittedCSV() async throws -> String {
        await saveTask?.value
        return try TransactionCsvCodec.encode(try await readSnapshot().transactions.filter { !$0.isDeleted })
    }

    func importJSON(_ content: String) throws -> FinanceDataImportResult {
        let snapshot = try FinanceDataJsonCodec.decode(content)
        return applyMerge(snapshot)
    }

    /// 普通 CSV 导入：先按 FinanceOS 标准列解析；失败时用宽容解析（中文/别名表头、自动去重 ID），
    /// 与旧 FinanceStore 语义一致，宽容解析在 shared 内完成。
    func importCSV(_ content: String) throws -> FinanceDataImportResult {
        let transactions: [Transaction]
        do {
            transactions = try TransactionCsvCodec.decode(content)
        } catch {
            let kmpCategories = categories.map { $0.toKMP() }
            let decoded = try TableTransactionImporter.shared.decodeCsvText(
                content: content,
                categories: kmpCategories,
                importedAtEpochMillis: clock.nowMillis()
            )
            guard !decoded.requiresCurrencyConversion else {
                throw DataTransferError(message: "此外币账单须先换算为人民币。请在 macOS 或 Android 端预览并换算。")
            }
            transactions = decoded.transactions.map(Transaction.init(kmp:))
        }
        return applyMerge(FinanceDataSnapshot(transactions: transactions, categories: [], budgets: []))
    }

    /// 导入原始文件（XLSX 或 CSV 文本），宽容解析在 shared 内完成，ID 与 Android 端一致。
    func importSpreadsheetFile(_ data: Data) throws -> FinanceDataImportResult {
        let bytes = KotlinByteArray(size: Int32(data.count)) { index in
            KotlinByte(char: Int8(bitPattern: data[Int(index.intValue)]))
        }
        let categories = categories.map { $0.toKMP() }
        let decoded = try TableTransactionImporter.shared.decode(
            bytes: bytes,
            categories: categories,
            importedAtEpochMillis: clock.nowMillis()
        )
        guard !decoded.requiresCurrencyConversion else {
            throw DataTransferError(message: "此外币账单须先换算为人民币。请在 macOS 或 Android 端预览并换算。")
        }
        let transactions = decoded.transactions.map(Transaction.init(kmp:))
        return applyMerge(FinanceDataSnapshot(transactions: transactions, categories: [], budgets: []))
    }

    func restoreFromBackup(_ content: String) throws -> FinanceDataImportResult {
        let snapshot = try FinanceDataJsonCodec.decode(content)
        let categories = snapshot.categories.isEmpty ? DefaultCategories.all : snapshot.categories
        let restored = FinanceDataSnapshot(transactions: snapshot.transactions, categories: categories, budgets: snapshot.budgets)
        apply(snapshot: restored, ledger: LedgerIndex(restored.transactions))
        persistReplaceAll(snapshot: restored)
        return FinanceDataImportResult(
            transactionCount: restored.transactions.count,
            categoryCount: restored.categories.count,
            budgetCount: restored.budgets.count
        )
    }

    @discardableResult
    func applyMerge(_ snapshot: FinanceDataSnapshot) -> FinanceDataImportResult {
        var newTransactions = 0
        var newCategories = 0
        var newBudgets = 0

        if !snapshot.transactions.isEmpty {
            var records = Dictionary(ledger.records.map { ($0.id, $0) }, uniquingKeysWith: { _, latest in latest })
            for transaction in snapshot.transactions {
                if let local = records[transaction.id],
                   transaction.updatedAt < local.updatedAt || transaction == local { continue }
                records[transaction.id] = transaction
                newTransactions += 1
            }
            if newTransactions > 0 { setLedger(LedgerIndex(Array(records.values))) }
        }
        if !snapshot.categories.isEmpty {
            var records = Dictionary(allCategories.map { ($0.id, $0) }, uniquingKeysWith: { _, latest in latest })
            for category in snapshot.categories {
                if let local = records[category.id], category.updatedAt <= local.updatedAt { continue }
                records[category.id] = category
                newCategories += 1
            }
            if newCategories > 0 { setCategories(records.values.sorted { $0.id < $1.id }) }
        }
        for budget in snapshot.budgets {
            if let index = allBudgets.firstIndex(where: { $0.id == budget.id }) {
                if budget.updatedAt > allBudgets[index].updatedAt {
                    allBudgets[index] = budget
                    newBudgets += 1
                }
            } else {
                allBudgets.append(budget)
                newBudgets += 1
            }
        }
        persistMerge(snapshot: snapshot)
        return FinanceDataImportResult(
            transactionCount: newTransactions,
            categoryCount: newCategories,
            budgetCount: newBudgets
        )
    }

    func exportJSON() throws -> String {
        try FinanceDataJsonCodec.encode(currentSnapshot())
    }

    func exportCSV() throws -> String {
        try TransactionCsvCodec.encode(transactions)
    }
}

/// 视图层的 FinanceStore 名称保持不变，App/Widget 只需替换数据来源。
typealias FinanceStore = FinanceStoreAdapter
