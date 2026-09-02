import Foundation
import FinanceOSCore

/// 无 Xcode 环境下的纯 Swift 移植校验。
/// 与 Android 端 `shared` 的用例对齐，任何失败都以非零码退出。
enum FinanceOSCheckRunner {
    static func run() {
        checkEqual(parseAmountInMinorUnits("12.34"), 1234, "parse 12.34")
        checkEqual(parseAmountInMinorUnits("0.5"), 50, "parse 0.5")
        checkEqual(parseAmountInMinorUnits(".5"), 50, "parse .5")
        if let commaInput = FinanceOSCore.normalizeAmountInput("12,3") {
            checkEqual(FinanceOSCore.parseAmountInMinorUnits(commaInput), 1230, "parse comma via normalize")
        } else {
            fputs("FAIL: comma normalize returned nil\n", stderr)
            exit(1)
        }
        checkEqual(parseAmountInMinorUnits("0", allowZero: true), 0, "allowZero")
        check(parseAmountInMinorUnits("abc") == nil, "reject abc")
        check(normalizeAmountInput("12,3") == "12.3", "normalize comma")
        check(normalizeAmountInput("12.3.4") == nil, "reject double dot")
        checkEqual(formatMoney(1234567), "¥12,345.67", "format grouping")
        checkEqual(formatMoney(5), "¥0.05", "format cents")
        checkEqual(formatMoney(-500), "-¥5.00", "format negative")

        checkEqual(FinanceOSCore.BudgetMonth(year: 2024, month: 2).daysInMonth, 29, "leap year")
        checkEqual(FinanceOSCore.BudgetMonth(year: 1900, month: 2).daysInMonth, 28, "century non-leap")
        checkEqual(FinanceOSCore.BudgetMonth(year: 2026, month: 12).next(), FinanceOSCore.BudgetMonth(year: 2027, month: 1), "next wraps")
        let period = FinanceOSCore.BudgetMonth(year: 2026, month: 9).period()
        check(period.contains(Calendar.current.date(byAdding: .day, value: 1, to: period.startInclusive)!), "period contains")
        check(!period.contains(period.endExclusive), "period half-open")

        runCalculations(period: period)
        runCodecs()
        runFlexibleCsv()
        runBillStyleCsv()
        MainActor.assumeIsolated {
            runStoreChecks(month: FinanceOSCore.BudgetMonth(year: 2026, month: 9))
        }
        print("All FinanceOS shared-domain checks passed ✔")
    }

    // MARK: - 计算

    private static func runCalculations(period: FinanceOSCore.MonthPeriod) {
        let base = Date(timeIntervalSince1970: 1_788_000_000_000)
        let summary = FinanceOSCore.MonthlySummaryCalculator.calculate([
            FinanceOSCore.Transaction(id: "t1", amount: 10000, type: .income, categoryId: "system-income", dateTime: base),
            FinanceOSCore.Transaction(id: "t2", amount: 2500, type: .expense, categoryId: "system-food", dateTime: base.addingTimeInterval(1000)),
            FinanceOSCore.Transaction(id: "t3", amount: 1500, type: .expense, categoryId: "system-food", dateTime: base.addingTimeInterval(2000)),
        ])
        checkEqual(summary.totalIncome, 10000, "income total")
        checkEqual(summary.totalExpense, 4000, "expense total")
        checkEqual(summary.netChange, 6000, "net")
        checkEqual(summary.categoryRanking.first?.categoryId, "system-food", "ranking head")

        let month = period.month
        let status = FinanceOSCore.BudgetStatusCalculator.calculate(
            summary: summary,
            budgets: [
                FinanceOSCore.Budget(id: "total", month: month, amountLimit: 10000),
                FinanceOSCore.Budget(id: "food", month: month, amountLimit: 2500, categoryId: "system-food"),
            ]
        )
        checkEqual(status.total.amountRemaining, 6000, "total remaining")
        checkEqual(status.categories["system-food"]?.isOverBudget, true, "category over budget")

        let cal = Calendar.current
        let startOfToday = cal.date(byAdding: .day, value: 9, to: period.startInclusive)!
        let daily = FinanceOSCore.DailyAvailableBudgetCalculator.calculate(
            period: month.period(calendar: cal),
            currentDayOfMonth: 10,
            startOfToday: startOfToday,
            totalBudget: FinanceOSCore.Budget(id: "total", month: month, amountLimit: 10_000),
            transactions: [
                FinanceOSCore.Transaction(id: "y", amount: 3000, type: .expense, categoryId: "system-food", dateTime: startOfToday.addingTimeInterval(-86_400)),
                FinanceOSCore.Transaction(id: "d", amount: 500, type: .expense, categoryId: "system-food", dateTime: startOfToday.addingTimeInterval(3_600)),
            ]
        )
        checkEqual(daily?.amountRemaining, 7000, "daily remaining")
        checkEqual(daily?.remainingDays, 21, "remaining days")
        checkEqual(daily?.dailyAmount, 333, "daily amount floor")
        checkEqual(daily?.isOverBudget, false, "not over")

        let trendMonths = FinanceOSCore.ExpenseTrendCalculator.monthTrendPeriods(anchorMonth: month, count: 6)
        checkEqual(trendMonths.first?.key, "2026-04", "trend first bucket")
        checkEqual(trendMonths.last?.key, "2026-09", "trend last bucket")
    }

    // MARK: - 编解码

    private static func runCodecs() {
        let base = Date(timeIntervalSince1970: 1_788_000_000_000)
        let month = FinanceOSCore.BudgetMonth(year: 2026, month: 9)
        let snapshot = FinanceOSCore.FinanceDataSnapshot(
            transactions: [
                FinanceOSCore.Transaction(id: "t-2", amount: 1250, type: .expense, categoryId: "system-food", accountId: "微信", dateTime: base, note: "午饭,含\"奶茶\""),
                FinanceOSCore.Transaction(id: "t-1", amount: 500000, type: .income, categoryId: "system-income", dateTime: base.addingTimeInterval(-100), note: "工资"),
            ],
            categories: FinanceOSCore.DefaultCategories.all,
            budgets: [FinanceOSCore.Budget(id: "b-1", month: month, amountLimit: 1000000)]
        )
        do {
            let json = try FinanceOSCore.FinanceDataJsonCodec.encode(snapshot)
            let decoded = try FinanceOSCore.FinanceDataJsonCodec.decode(json)
            checkEqual(decoded.transactions.count, 2, "json tx count")
            var byId: [String: FinanceOSCore.Transaction] = [:]
            for transaction in decoded.transactions { byId[transaction.id] = transaction }
            checkEqual(byId["t-2"]?.amount, 1250, "json amount")
            checkEqual(byId["t-2"]?.note, "午饭,含\"奶茶\"", "json note")

            let csv = FinanceOSCore.TransactionCsvCodec.encode(snapshot.transactions)
            check(csv.hasPrefix("\u{FEFF}"), "csv BOM")
            let parsed = try FinanceOSCore.TransactionCsvCodec.decode(csv)
            checkEqual(parsed.count, 2, "csv count")
            checkEqual(parsed.first { $0.id == "t-2" }?.note, "午饭,含\"奶茶\"", "csv escaping")
            checkEqual(parsed.first { $0.id == "t-1" }?.amount, 500000, "csv amount")
        } catch {
            fputs("FAIL: codec error — \(error)\n", stderr)
            exit(1)
        }

        do {
            _ = try FinanceOSCore.FinanceDataJsonCodec.decode("{\"format\":\"other\",\"schema_version\":1}")
            fputs("FAIL: foreign format accepted\n", stderr)
            exit(1)
        } catch {}
        do {
            _ = try FinanceOSCore.TransactionCsvCodec.decode("\u{FEFF}id,amount_minor,amount,type,category_id,account_id,date_time_epoch_millis,note\nt-1,bad,1.00,EXPENSE,x,,1,")
            fputs("FAIL: bad csv row accepted\n", stderr)
            exit(1)
        } catch {}
    }

    // MARK: - 宽容 CSV（与 Android TableTransactionImporter 同语义）

    private static func runFlexibleCsv() {
        let csv = [
            "\u{FEFF}日期,金额,类型,分类,账户,备注",
            "2026-09-01 08:30,25.50,支出,餐饮,微信,\"午饭,含\"\"奶茶\"\"\"",
            "2026/9/2,45800,收入,工资/生活费,,",
        ].joined(separator: "\n")
        do {
            let categories = FinanceOSCore.DefaultCategories.all
            let standard = try FinanceOSCore.FlexibleSpreadsheetImporter.normalizeCSV(csv, categories: categories)
            let decoded = try FinanceOSCore.TransactionCsvCodec.decode(standard)
            checkEqual(decoded.count, 2, "flexible csv row count")
            let first = decoded.first { $0.amount == 2550 }
            check(first != nil, "flexible csv amount yuan->minor")
            checkEqual(first?.categoryId, "system-food", "flexible csv category name map")
            checkEqual(first?.accountId, "微信", "flexible csv account")
            check(first?.note == nil || (first?.note ?? "").isEmpty, "flexible csv note now counterparty only")
            let income = decoded.first { $0.type == .income }
            checkEqual(income?.categoryId, "system-income", "flexible csv income category")
            checkEqual(income?.amount, 4580000, "flexible csv income amount")
        } catch {
            fputs("FAIL: flexible csv — \(error)\n", stderr)
            exit(1)
        }
    }

    // MARK: - 微信账单样式（顶部说明行 + 表头自动定位）

    private static func runBillStyleCsv() {
        let csv = [
            "微信支付账单明细",
            "导出时间：2026-09-02 10:00:00",
            "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注",
            "2026-08-31 08:12:00,,美团,,外卖订单,支出,12.5,零钱,支付成功,o1,m1,",
        ].joined(separator: "\n")
        do {
            let categories = FinanceOSCore.DefaultCategories.all
            let standard = try FinanceOSCore.FlexibleSpreadsheetImporter.normalizeCSV(csv, categories: categories)
            let decoded = try FinanceOSCore.TransactionCsvCodec.decode(standard)
            checkEqual(decoded.count, 1, "bill style row count")
            let row = decoded[0]
            checkEqual(row.amount, 1250, "bill style amount")
            checkEqual(row.type, .expense, "bill style type")
            checkEqual(row.categoryId, "system-other", "bill style default category")
            checkEqual(row.accountId, "零钱", "bill style account")
            checkEqual(row.note, "美团", "bill style note is counterparty")
        } catch {
            fputs("FAIL: bill style csv — \(error)\n", stderr)
            exit(1)
        }
    }

    // MARK: - Store

    @MainActor
    private static func runStoreChecks(month: FinanceOSCore.BudgetMonth) {
        struct Location: FinanceOSCore.StoreLocationProviding {
            let directory: URL
            func storeURL() -> URL { directory.appendingPathComponent("store.json") }
        }
        let store = FinanceOSCore.FinanceStore(
            location: Location(directory: FileManager.default.temporaryDirectory.appendingPathComponent("checks-\(UUID().uuidString)"))
        )
        checkEqual(store.categories.count, FinanceOSCore.DefaultCategories.all.count, "seed categories")
        check(store.category(id: "system-food") != nil, "system category present")

        let today = Date()
        store.addTransaction(FinanceOSCore.Transaction(id: "t1", amount: 2500, type: .expense, categoryId: "system-food", dateTime: today))
        store.setTotalBudget(month: month, amountLimit: 100_000)
        checkEqual(store.transactions.count, 1, "add tx")
        checkEqual(store.totalBudget(month: month)?.amountLimit, 100_000, "set budget")

        store.updateTransaction(FinanceOSCore.Transaction(id: "t1", amount: 3000, type: .expense, categoryId: "system-food", dateTime: today))
        checkEqual(store.transactions.first?.amount, 3000, "update tx")

        let expenseCategories = store.categories(for: .expense)
        check(expenseCategories.contains { $0.id == "system-other" }, "common category usable for expense")
        check(!expenseCategories.contains { $0.id == "system-income" }, "income category not expense")

        store.deleteTransaction(id: "t1")
        checkEqual(store.transactions.count, 0, "delete tx")

        let imported = try? store.importJSON("""
        {"format":"financeos-backup","schema_version":1,
         "transactions":[{"id":"r1","amount_minor":100,"type":"EXPENSE","category_id":"system-food","date_time_epoch_millis":1788000000000}],
         "categories":[],"budgets":[]}
        """)
        checkEqual(imported?.transactionCount, 1, "import merge")
        checkEqual(store.transactions.count, 1, "import applied")

        let restored = try? store.restoreFromBackup("""
        {"format":"financeos-backup","schema_version":1,
         "transactions":[{"id":"bk1","amount_minor":1,"type":"EXPENSE","category_id":"system-food","date_time_epoch_millis":1788000000000}],
         "categories":[],"budgets":[]}
        """)
        checkEqual(restored?.transactionCount, 1, "restore count")
        checkEqual(store.transactions.map(\.id), ["bk1"], "restore replaces")
        check(store.category(id: "system-food") != nil, "defaults re-seeded after restore")
    }

    // MARK: - 断言工具

    private static func check(_ condition: @autoclosure () -> Bool, _ message: String) {
        guard condition() else {
            fputs("FAIL: \(message)\n", stderr)
            exit(1)
        }
    }

    private static func checkEqual<T: Equatable>(_ lhs: T, _ rhs: T, _ message: String) {
        guard lhs == rhs else {
            fputs("FAIL: \(message) — got \(lhs), want \(rhs)\n", stderr)
            exit(1)
        }
    }
}
