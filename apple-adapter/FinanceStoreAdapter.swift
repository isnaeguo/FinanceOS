import Foundation
import Observation
import FinanceOSShared

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

/// 个人财务本地存储的 Swift 视图适配器。
///
/// 业务内核是 KMP `shared`（Room 数据库 + v2 合并/编解码/去重）。本类维护一份与视图同构的
/// 内存投影：所有写操作同步更新内存（视图立即一致）并在后台落到 Room；Room 的响应式查询
/// 回流后以 shared 的裁决结果为准。首次启动检测旧 `store.json`：可解析则导入 Room 并把旧文件
/// 改名为 `.migrated-<时间戳>`，解析失败保留现场并提示（沿用 `.damaged-*` 惯例）。
@MainActor
@Observable
final class FinanceStoreAdapter {
    private(set) var allTransactions: [Transaction] = []
    private(set) var allCategories: [Category] = []
    private(set) var allBudgets: [Budget] = []
    private(set) var lastError: String?
    private(set) var loadDate = Date()

    let location: StoreLocationProviding
    let calendar: Calendar

    var saveTask: Task<Void, Never>?

    private let database: FinanceOsDatabase
    private let dataRepository: LocalFinanceDataRepository
    private let transactionRepository: LocalTransactionRepository
    private let clock = SystemEpochClock()
    private var flowTasks: [Task<Void, Never>] = []
    private var lastSnapshot: FinanceDataSnapshot?

    convenience init(location: StoreLocationProviding = DefaultStoreLocation(), calendar: Calendar = .current) {
        self.init(location: location, databasePath: nil, calendar: calendar)
    }

    /// [databasePath] 供测试注入临时数据库位置；生产使用默认 App Group/Application Support 路径。
    init(location: StoreLocationProviding, databasePath: String?, calendar: Calendar) {
        self.location = location
        self.calendar = calendar
        database = AppleDatabaseLocationKt.createAppleFinanceOsDatabase(path: databasePath)
        dataRepository = LocalFinanceDataRepository(database: database)
        transactionRepository = LocalTransactionRepository(dao: database.transactionDao(), clock: clock)
        migrateLegacyStoreIfNeeded()
        scheduleSave()
        startObservation()
    }

    // MARK: - 视图可见数据（不含墓碑）

    var transactions: [Transaction] {
        allTransactions.filter { !$0.isDeleted }
            .sorted { $0.dateTime != $1.dateTime ? $0.dateTime > $1.dateTime : $0.id < $1.id }
    }

    var categories: [Category] { allCategories.filter { !$0.isDeleted }.sorted { $0.id < $1.id } }

    var budgets: [Budget] { allBudgets.filter { !$0.isDeleted } }

    var knownAccounts: [String] {
        Array(Set(allTransactions.compactMap(\.accountId))).sorted()
    }

    // MARK: - 旧 store.json 迁移

    /// 历史 store.json 可能出现的位置：App Group 启用前的 Application Support 是存量主路径，
    /// 启用后的组容器是本轮新装路径；两者都检查，避免启用组能力后丢数据。
    private func legacyStoreCandidates() -> [URL] {
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

    private func migrateLegacyStoreIfNeeded() {
        let url = legacyStoreCandidates().first { FileManager.default.fileExists(atPath: $0.path) }
        guard let url else { return }
        do {
            let content = try String(contentsOf: url, encoding: .utf8)
            let snapshot = try FinanceDataJsonCodec.decode(content)
            let migratedURL = url.deletingPathExtension()
                .appendingPathExtension("migrated-\(Int(Date().timeIntervalSince1970)).json")
            try? FileManager.default.removeItem(at: migratedURL)
            try FileManager.default.moveItem(at: url, to: migratedURL)
            let categories = snapshot.categories.isEmpty ? DefaultCategories.all : snapshot.categories
            allTransactions = snapshot.transactions
            allCategories = categories
            allBudgets = snapshot.budgets
            persistReplaceAll(snapshot: FinanceDataSnapshot(transactions: snapshot.transactions, categories: categories, budgets: snapshot.budgets))
        } catch {
            // 解析失败不删除原文件：保留现场并提示，与旧 `.damaged-*` 惯例一致。
            let aside = url.deletingPathExtension().appendingPathExtension("damaged-\(Int(loadDate.timeIntervalSince1970)).json")
            try? FileManager.default.removeItem(at: aside)
            try? FileManager.default.moveItem(at: url, to: aside)
            lastError = "本地数据文件无法读取（\(error.localizedDescription)），已保留原文件并重新初始化。"
            allCategories = DefaultCategories.all
        }
    }

    /// 启动后从 Room 全量加载（含墓碑），供 UI 使用与导出缓存。
    private func startObservation() {
        let refresh: Task<Void, Never> = Task { [weak self] in
            guard let self else { return }
            await self.refreshFromDatabase()
        }
        flowTasks.append(refresh)
    }

    private func refreshFromDatabase() async {
        if let snapshot = try? await readSnapshot() {
            apply(snapshot: snapshot)
        }
        NotificationCenter.default.post(name: .financeosDataDidChange, object: nil)
    }

    private func apply(snapshot: FinanceDataSnapshot) {
        allTransactions = snapshot.transactions.sorted { $0.dateTime > $1.dateTime }
        allCategories = snapshot.categories
        allBudgets = snapshot.budgets
        lastSnapshot = snapshot
        NotificationCenter.default.post(name: .financeosDataDidChange, object: nil)
    }

    // MARK: - 持久化

    /// 同步 API 背后的写队列：内存已先行更新，这里把变更落到 shared。
    private func persist(_ operation: @escaping @Sendable () async -> Void) {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            await operation()
            await self?.refreshFromDatabase()
        }
    }

    private func persistReplaceAll(snapshot: FinanceDataSnapshot) {
        persist { [dataRepository] in
            _ = try? await dataRepository.replaceAll(snapshot: snapshot.toKMP())
        }
    }

    private func persistMerge(snapshot: FinanceDataSnapshot) {
        persist { [dataRepository] in
            _ = try? await dataRepository.merge(snapshot: snapshot.toKMP())
        }
    }

    private func readSnapshot() async throws -> FinanceDataSnapshot {
        let kmp = try await dataRepository.snapshot()
        return FinanceDataSnapshot(kmp: kmp)
    }

    func scheduleSave() {
        // Room 已负责持久化，保留空实现以兼容旧调用点。
    }

    var storeLocationDescription: String { location.storeURL().path }

    func storeURL() -> URL { location.storeURL() }

    // MARK: - 分类查询

    func category(id: String?) -> Category? {
        guard let id else { return nil }
        return categories.first { $0.id == id }
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
            _ = try? await transactionRepository.add(transaction: kmp)
        }
    }

    func updateTransaction(_ transaction: Transaction) {
        var stamped = transaction
        stamped.updatedAt = clock.nowMillis()
        stamped.deletedAt = nil
        upsertLocally(stamped)
        let kmp = stamped.toKMP()
        persist { [transactionRepository] in
            _ = try? await transactionRepository.add(transaction: kmp)
        }
    }

    func deleteTransaction(id: String) {
        guard let existing = allTransactions.first(where: { $0.id == id }) else { return }
        let now = clock.nowMillis()
        var tombstone = existing
        tombstone.deletedAt = now
        tombstone.updatedAt = now
        upsertLocally(tombstone)
        persist { [transactionRepository] in
            _ = try? await transactionRepository.delete(id: id)
        }
    }

    private func upsertLocally(_ transaction: Transaction) {
        allTransactions.removeAll { $0.id == transaction.id }
        allTransactions.append(transaction)
        allTransactions.sort { $0.dateTime > $1.dateTime }
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
        allCategories.removeAll { $0.id == category.id }
        allCategories.append(category)
        persistMerge(snapshot: FinanceDataSnapshot(transactions: [], categories: [category], budgets: []))
        return category
    }

    func deleteCategory(id: String) -> Bool {
        guard let category = allCategories.first(where: { $0.id == id }), !category.isSystem else { return false }
        var tombstone = category
        tombstone.deletedAt = clock.nowMillis()
        tombstone.updatedAt = tombstone.deletedAt ?? 0
        allCategories.removeAll { $0.id == id }
        allCategories.append(tombstone)
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
        let now = clock.nowMillis()
        if amountLimit == 0 {
            guard let existing = allBudgets.first(where: { $0.month == month && $0.categoryId == categoryId }) else { return }
            var tombstone = existing
            tombstone.deletedAt = now
            tombstone.updatedAt = now
            allBudgets.removeAll { $0.id == existing.id }
            allBudgets.append(tombstone)
            persistMerge(snapshot: FinanceDataSnapshot(transactions: [], categories: [], budgets: [tombstone]))
        } else if let index = allBudgets.firstIndex(where: { $0.month == month && $0.categoryId == categoryId }) {
            var updated = allBudgets[index]
            updated.amountLimit = amountLimit
            updated.updatedAt = now
            updated.deletedAt = nil
            allBudgets[index] = updated
            persistMerge(snapshot: FinanceDataSnapshot(transactions: [], categories: [], budgets: [updated]))
        } else {
            let budget = Budget(
                id: "budget-\(UUID().uuidString.lowercased())",
                month: month,
                amountLimit: amountLimit,
                categoryId: categoryId,
                updatedAt: now
            )
            allBudgets.append(budget)
            persistMerge(snapshot: FinanceDataSnapshot(transactions: [], categories: [], budgets: [budget]))
        }
    }

    func totalBudget(month: BudgetMonth) -> Budget? {
        budgets.first { $0.month == month && $0.categoryId == nil }
    }

    func categoryBudget(month: BudgetMonth, categoryId: String) -> Budget? {
        budgets.first { $0.month == month && $0.categoryId == categoryId }
    }

    // MARK: - 派生查询（显示层计算，输入为当前可见数据）

    func monthlyTransactions(in period: MonthPeriod) -> [Transaction] {
        transactions.filter { period.contains($0.dateTime) }
    }

    func monthlySummary(in period: MonthPeriod) -> MonthlySummary {
        SummaryCalculations.monthlySummary(transactions: monthlyTransactions(in: period))
    }

    func budgetStatus(in period: MonthPeriod) -> MonthlyBudgetStatus {
        let monthBudgets = allBudgets.filter { !$0.isDeleted && $0.month == period.month }
        return SummaryCalculations.budgetStatus(summary: monthlySummary(in: period), budgets: monthBudgets)
    }

    func dailyAvailableBudget(today: Date = Date(), calendar: Calendar = .current) -> DailyAvailableBudget? {
        let month = BudgetMonth(year: calendar.component(.year, from: today), month: calendar.component(.month, from: today))
        let period = month.period(calendar: calendar)
        let startOfToday = calendar.startOfDay(for: today)
        let day = calendar.component(.day, from: today)
        return SummaryCalculations.dailyAvailableBudget(
            period: period,
            currentDayOfMonth: day,
            startOfToday: startOfToday,
            totalBudget: totalBudget(month: month),
            transactions: monthlyTransactions(in: period)
        )
    }

    func monthlyExpenseTrend(anchorMonth: BudgetMonth, count: Int = 6) -> [ExpenseTrendPoint] {
        let periods = SummaryCalculations.monthTrendPeriods(anchorMonth: anchorMonth, count: count, calendar: calendar)
        guard let first = periods.first, let last = periods.last else { return [] }
        let rangeTransactions = allTransactions.filter { !$0.isDeleted && $0.dateTime >= first.startInclusive && $0.dateTime < last.endExclusive }
        return SummaryCalculations.expenseTrend(periods: periods, transactions: rangeTransactions)
    }

    func dailyExpenseTrendData(anchorDay: Date = Date(), days: Int) -> [DailyTrendDatum] {
        let periods = SummaryCalculations.dailyTrendPeriods(anchorDay: anchorDay, days: days, calendar: calendar)
        guard let first = periods.first, let last = periods.last else { return [] }
        let rangeTransactions = allTransactions.filter { !$0.isDeleted && $0.dateTime >= first.startInclusive && $0.dateTime < last.endExclusive }
        let points = SummaryCalculations.expenseTrend(periods: periods, transactions: rangeTransactions)
        return zip(periods, points).map { DailyTrendDatum(date: $0.startInclusive, amount: $1.amount) }
    }

    func recentTransactions(limit: Int) -> [Transaction] {
        Array(transactions.prefix(limit))
    }

    // MARK: - 导入 / 导出 / 备份

    func currentSnapshot() -> FinanceDataSnapshot {
        FinanceDataSnapshot(transactions: transactions, categories: categories, budgets: budgets)
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
        let transactions = decoded.transactions.map(Transaction.init(kmp:))
        return applyMerge(FinanceDataSnapshot(transactions: transactions, categories: [], budgets: []))
    }

    func restoreFromBackup(_ content: String) throws -> FinanceDataImportResult {
        let snapshot = try FinanceDataJsonCodec.decode(content)
        let categories = snapshot.categories.isEmpty ? DefaultCategories.all : snapshot.categories
        let restored = FinanceDataSnapshot(transactions: snapshot.transactions, categories: categories, budgets: snapshot.budgets)
        apply(snapshot: restored)
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

        for transaction in snapshot.transactions {
            if let local = allTransactions.first(where: { $0.id == transaction.id }) {
                if transaction.updatedAt > local.updatedAt || (transaction.updatedAt == local.updatedAt && transaction != local) {
                    upsertLocally(transaction)
                    newTransactions += 1
                }
            } else {
                upsertLocally(transaction)
                newTransactions += 1
            }
        }
        for category in snapshot.categories {
            if allCategories.contains(where: { $0.id == category.id }) {
                if let index = allCategories.firstIndex(where: { $0.id == category.id }),
                   category.updatedAt > allCategories[index].updatedAt {
                    allCategories[index] = category
                    newCategories += 1
                }
            } else {
                allCategories.append(category)
                newCategories += 1
            }
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
        let source = lastSnapshot ?? currentSnapshot()
        return try FinanceDataJsonCodec.encode(source)
    }

    func exportCSV() throws -> String {
        try TransactionCsvCodec.encode(transactions)
    }
}

/// 视图层的 FinanceStore 名称保持不变，App/Widget 只需替换数据来源。
typealias FinanceStore = FinanceStoreAdapter
