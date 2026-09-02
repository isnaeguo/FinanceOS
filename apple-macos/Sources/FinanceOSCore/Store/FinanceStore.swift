import Foundation
import Observation

extension Notification.Name {
    /// 数据成功落盘后发出，供小组件等外部渠道即时感知数据变化。
    public static let financeosDataDidChange = Notification.Name("financeos.dataDidChange")
}

/// 本地存储位置提供者，便于测试注入临时目录。
public protocol StoreLocationProviding: Sendable {
    func storeURL() -> URL
}

/// 将数据保存在 `~/Library/Application Support/FinanceOS/store.json`。
public struct DefaultStoreLocation: StoreLocationProviding {
    public init() {}

    public func storeURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("FinanceOS", isDirectory: true)
            .appendingPathComponent("store.json")
    }
}

/// 个人财务本地存储：内存模型 + 与 Android 端同格式的 JSON 快照落盘。
///
/// 所有金额均为最小货币单位（分）。写入采用“临时文件 + 原子替换”，
/// 与“备份文件创建与原子恢复”的数据文件约定一致。
@MainActor
@Observable
public final class FinanceStore {
    private(set) var allTransactions: [Transaction] = []
    private(set) var allCategories: [Category] = []
    private(set) var allBudgets: [Budget] = []
    private(set) var lastError: String?

    public let location: StoreLocationProviding
    public let calendar: Calendar
    public var saveTask: Task<Void, Never>?
    public let loadDate: Date

    public var transactions: [Transaction] { allTransactions }
    public var categories: [Category] { allCategories }
    public var budgets: [Budget] { allBudgets }

    // MARK: - 初始化

    public init(location: StoreLocationProviding = DefaultStoreLocation(), calendar: Calendar = .current) {
        self.location = location
        self.calendar = calendar
        self.loadDate = Date()
        loadOrSeed()
    }

    public func loadOrSeed() {
        let url = location.storeURL()
        if FileManager.default.fileExists(atPath: url.path) {
            do {
                let content = try String(contentsOf: url, encoding: .utf8)
                let snapshot = try FinanceDataJsonCodec.decode(content)
                allTransactions = snapshot.transactions.sorted { $0.dateTime > $1.dateTime }
                allCategories = snapshot.categories
                allBudgets = snapshot.budgets
                ensureDefaultCategories()
                scheduleSave()
                return
            } catch {
                // 损坏的数据不覆盖用户文件：改用内存备份文件名保留现场，再从默认分类重新开始。
                lastError = "本地数据文件无法读取（\(error.localizedDescription)），已保留原文件并重新初始化。"
                keepDamagedFileAside(at: url)
            }
        }
        allCategories = DefaultCategories.all
        allTransactions = []
        allBudgets = []
        scheduleSave()
    }

    public func keepDamagedFileAside(at url: URL) {
        let aside = url.deletingPathExtension().appendingPathExtension("damaged-\(Int(loadDate.timeIntervalSince1970)).json")
        try? FileManager.default.removeItem(at: aside)
        try? FileManager.default.moveItem(at: url, to: aside)
    }

    /// 保证内置系统分类始终存在（ID 固定，导入的数据缺失时补齐）。
    public func ensureDefaultCategories() {
        let existing = Set(allCategories.map(\.id))
        for category in DefaultCategories.all where !existing.contains(category.id) {
            allCategories.append(category)
        }
    }

    // MARK: - 存储位置

    public var storeLocationDescription: String {
        location.storeURL().path
    }

    public func storeURL() -> URL {
        location.storeURL()
    }

    // MARK: - 持久化

    public func scheduleSave() {
        saveTask?.cancel()
        let snapshot = FinanceDataSnapshot(
            transactions: allTransactions,
            categories: allCategories,
            budgets: allBudgets
        )
        let url = location.storeURL()
        saveTask = Task.detached(priority: .utility) {
            do {
                let content = try FinanceDataJsonCodec.encode(snapshot)
                let directory = url.deletingLastPathComponent()
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let temporary = directory.appendingPathComponent(".store-\(UUID().uuidString).tmp")
                try content.write(to: temporary, atomically: true, encoding: .utf8)
                _ = try FileManager.default.replaceItemAt(url, withItemAt: temporary)
                // 落盘成功后再通知外部渠道（如小组件）刷新，避免对同一份数据重复读取。
                NotificationCenter.default.post(name: .financeosDataDidChange, object: nil)
            } catch {
                // 落盘失败不中断使用；下次修改会再次尝试。
            }
        }
    }

    // MARK: - 分类查询

    public func category(id: String?) -> Category? {
        guard let id else { return nil }
        return allCategories.first { $0.id == id }
    }

    public func categories(for type: TransactionType) -> [Category] {
        allCategories.filter { $0.accepts(type) }
    }

    // MARK: - 流水 CRUD

    public func addTransaction(_ transaction: Transaction) {
        allTransactions.append(transaction)
        allTransactions.sort { $0.dateTime > $1.dateTime }
        scheduleSave()
    }

    public func updateTransaction(_ transaction: Transaction) {
        guard let index = allTransactions.firstIndex(where: { $0.id == transaction.id }) else {
            addTransaction(transaction)
            return
        }
        allTransactions[index] = transaction
        allTransactions.sort { $0.dateTime > $1.dateTime }
        scheduleSave()
    }

    public func deleteTransaction(id: String) {
        allTransactions.removeAll { $0.id == id }
        scheduleSave()
    }

    // MARK: - 分类 CRUD

    public func addCategory(name: String, type: CategoryType, iconKey: String) -> Category {
        let category = Category(
            id: "user-\(UUID().uuidString.lowercased())",
            name: name.trimmingCharacters(in: .whitespaces),
            type: type,
            iconKey: iconKey,
            isSystem: false
        )
        allCategories.append(category)
        scheduleSave()
        return category
    }

    public func deleteCategory(id: String) -> Bool {
        guard let category = allCategories.first(where: { $0.id == id }), !category.isSystem else { return false }
        allCategories.removeAll { $0.id == id }
        scheduleSave()
        return true
    }

    // MARK: - 账户

    /// 历史流水中出现过的账户名，用于新增流水时的快速选择。
    public var knownAccounts: [String] {
        Array(Set(allTransactions.compactMap(\.accountId))).sorted()
    }

    // MARK: - 预算 CRUD

    /// 设置某月总预算（`categoryId == nil`）；额度为 0 表示移除。
    public func setTotalBudget(month: BudgetMonth, amountLimit: Int64) {
        setBudget(month: month, categoryId: nil, amountLimit: amountLimit)
    }

    public func setCategoryBudget(month: BudgetMonth, categoryId: String, amountLimit: Int64) {
        setBudget(month: month, categoryId: categoryId, amountLimit: amountLimit)
    }

    public func setBudget(month: BudgetMonth, categoryId: String?, amountLimit: Int64) {
        precondition(amountLimit >= 0, "Budget amountLimit must not be negative.")
        if amountLimit == 0 {
            allBudgets.removeAll { $0.month == month && $0.categoryId == categoryId }
        } else if let index = allBudgets.firstIndex(where: { $0.month == month && $0.categoryId == categoryId }) {
            allBudgets[index].amountLimit = amountLimit
        } else {
            allBudgets.append(Budget(id: UUID().uuidString, month: month, amountLimit: amountLimit, categoryId: categoryId))
        }
        scheduleSave()
    }

    public func totalBudget(month: BudgetMonth) -> Budget? {
        allBudgets.first { $0.month == month && $0.categoryId == nil }
    }

    public func categoryBudget(month: BudgetMonth, categoryId: String) -> Budget? {
        allBudgets.first { $0.month == month && $0.categoryId == categoryId }
    }

    // MARK: - 派生查询

    /// 指定月份内的流水，按时间倒序。
    public func monthlyTransactions(in period: MonthPeriod) -> [Transaction] {
        allTransactions.filter { period.contains($0.dateTime) }
    }

    public func monthlySummary(in period: MonthPeriod) -> MonthlySummary {
        MonthlySummaryCalculator.calculate(monthlyTransactions(in: period))
    }

    public func budgetStatus(in period: MonthPeriod) -> MonthlyBudgetStatus {
        let monthBudgets = allBudgets.filter { $0.month == period.month }
        return BudgetStatusCalculator.calculate(summary: monthlySummary(in: period), budgets: monthBudgets)
    }

    /// 当天的建议可用预算；未设置当月总预算时返回 `nil`。
    public func dailyAvailableBudget(today: Date = Date(), calendar: Calendar = .current) -> DailyAvailableBudget? {
        let month = BudgetMonth(year: calendar.component(.year, from: today), month: calendar.component(.month, from: today))
        let period = month.period(calendar: calendar)
        let startOfToday = calendar.startOfDay(for: today)
        let day = calendar.component(.day, from: today)
        return DailyAvailableBudgetCalculator.calculate(
            period: period,
            currentDayOfMonth: day,
            startOfToday: startOfToday,
            totalBudget: totalBudget(month: month),
            transactions: monthlyTransactions(in: period)
        )
    }

    /// 最近 `count` 个自然月（含当前月）的支出趋势，按时间升序。
    public func monthlyExpenseTrend(anchorMonth: BudgetMonth, count: Int = 6) -> [ExpenseTrendPoint] {
        let periods = ExpenseTrendCalculator.monthTrendPeriods(anchorMonth: anchorMonth, count: count, calendar: calendar)
        guard let first = periods.first, let last = periods.last else { return [] }
        let rangeTransactions = allTransactions.filter { $0.dateTime >= first.startInclusive && $0.dateTime < last.endExclusive }
        return ExpenseTrendCalculator.calculate(periods: periods, transactions: rangeTransactions)
    }

    /// 最近 `days` 天（含今天）的按日支出趋势，按时间升序，保留每桶的日期以便图表自动布轴。
    public func dailyExpenseTrendData(anchorDay: Date = Date(), days: Int) -> [DailyTrendDatum] {
        let periods = ExpenseTrendCalculator.dailyTrendPeriods(anchorDay: anchorDay, days: days, calendar: calendar)
        guard let first = periods.first, let last = periods.last else { return [] }
        let rangeTransactions = allTransactions.filter { $0.dateTime >= first.startInclusive && $0.dateTime < last.endExclusive }
        let points = ExpenseTrendCalculator.calculate(periods: periods, transactions: rangeTransactions)
        return zip(periods, points).map { period, point in
            DailyTrendDatum(date: period.startInclusive, amount: point.amount)
        }
    }

    /// 最近流水，按时间倒序。
    public func recentTransactions(limit: Int) -> [Transaction] {
        Array(allTransactions.prefix(limit))
    }

    // MARK: - 导入 / 导出 / 备份

    public func currentSnapshot() -> FinanceDataSnapshot {
        FinanceDataSnapshot(transactions: allTransactions, categories: allCategories, budgets: allBudgets)
    }

    /// 普通 JSON 导入：按 ID 合并，不删除未涉及的本机数据。
    public func importJSON(_ content: String) throws -> FinanceDataImportResult {
        let snapshot = try FinanceDataJsonCodec.decode(content)
        return applyMerge(snapshot)
    }

    /// 普通 CSV 导入：按 ID 合并流水，不删除未涉及的本机数据。
    /// 先按 FinanceOS 标准列解析；失败时用宽容解析（中文/别名表头、无 id 自动生成、元/分、
    /// 多种日期、宽容引号），兼容 Excel/WPS 另存的 CSV。
    public func importCSV(_ content: String) throws -> FinanceDataImportResult {
        func merge(_ csv: String) throws -> FinanceDataImportResult {
            let transactions = try TransactionCsvCodec.decode(csv)
            return applyMerge(FinanceDataSnapshot(transactions: transactions, categories: [], budgets: []))
        }
        do {
            return try merge(content)
        } catch is DataTransferError {
            let standard = try FlexibleSpreadsheetImporter.normalizeCSV(content, categories: allCategories)
            return try merge(standard)
        }
    }

    /// 导入用户选择的原始文件：自动识别 XLSX（zip）或 CSV 文本（UTF-8/GB18030）。
    /// XLSX 按行读取后走与 CSV 完全相同的宽容导入（含去重 ID 与 0.45 元过滤）。
    public func importSpreadsheetFile(_ data: Data) throws -> FinanceDataImportResult {
        // XLSX 解压依赖系统 ditto，仅 macOS 可用；iOS 端走文本/CSV 路径。
        #if os(macOS)
        if XlsxImportReader.isXlsx(data) {
            let grid = try XlsxImportReader.readFirstSheet(data)
            let csv = XlsxImportReader.gridToCSV(grid)
            return try importCSV(csv)
        }
        #endif
        let text = FlexibleSpreadsheetImporter.decodeSpreadsheetText(data)
        return try importCSV(text)
    }

    /// 备份恢复：完整替换本机数据（调用方负责先做二次确认）。
    public func restoreFromBackup(_ content: String) throws -> FinanceDataImportResult {
        let snapshot = try FinanceDataJsonCodec.decode(content)
        allTransactions = snapshot.transactions.sorted { $0.dateTime > $1.dateTime }
        allCategories = snapshot.categories
        allBudgets = snapshot.budgets
        ensureDefaultCategories()
        scheduleSave()
        return FinanceDataImportResult(
            transactionCount: snapshot.transactions.count,
            categoryCount: snapshot.categories.count,
            budgetCount: snapshot.budgets.count
        )
    }

    public func applyMerge(_ snapshot: FinanceDataSnapshot) -> FinanceDataImportResult {
        var writtenTransactions = 0
        var writtenCategories = 0
        var writtenBudgets = 0

        var transactionIds = Set(allTransactions.map(\.id))
        for transaction in snapshot.transactions where !transactionIds.contains(transaction.id) {
            allTransactions.append(transaction)
            transactionIds.insert(transaction.id)
            writtenTransactions += 1
        }

        var categoryIds = Set(allCategories.map(\.id))
        for category in snapshot.categories where !categoryIds.contains(category.id) {
            allCategories.append(category)
            categoryIds.insert(category.id)
            writtenCategories += 1
        }

        var budgetIds = Set(allBudgets.map(\.id))
        for budget in snapshot.budgets where !budgetIds.contains(budget.id) {
            allBudgets.append(budget)
            budgetIds.insert(budget.id)
            writtenBudgets += 1
        }

        allTransactions.sort { $0.dateTime > $1.dateTime }
        scheduleSave()
        return FinanceDataImportResult(
            transactionCount: writtenTransactions,
            categoryCount: writtenCategories,
            budgetCount: writtenBudgets
        )
    }

    /// 导出当前全部数据的 JSON（FinanceOS 备份格式）。
    public func exportJSON() throws -> String {
        try FinanceDataJsonCodec.encode(currentSnapshot())
    }

    /// 导出全部流水的 CSV（含 BOM）。
    public func exportCSV() throws -> String {
        TransactionCsvCodec.encode(allTransactions)
    }
}
