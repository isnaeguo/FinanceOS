import Foundation
import FinanceOSShared

@MainActor
enum FinanceOSSmokeRunner {
    static func run() async throws {
        // 1) 临时目录建库，验证 v2 schema 表结构可用。
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("fos-checks-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let dbPath = dir.appendingPathComponent("db.sqlite").path
        let db = AppleDatabaseLocationKt.createAppleFinanceOsDatabase(path: dbPath)
        let dataRepo = LocalFinanceDataRepository(database: db)
        let clock = CheckClock()
        let txnRepo = LocalTransactionRepository(dao: db.transactionDao(), clock: clock)

        // 2) 写入两条流水（一条更新一条墓碑），merge 后由 snapshot 全量导出。
        let date = KotlinInstant.Companion().fromEpochMilliseconds(epochMilliseconds: 1_786_350_600_000)
        let category = Category(
            id: "system-food", name: "餐饮", type: .expense, iconKey: "food",
            isSystem: true, updatedAt: 0, deletedAt: nil
        )
        let tx = Transaction(
            id: "tx-a", amount: 2350, type: .expense, categoryId: "system-food",
            accountId: nil, dateTime: date, note: "冒烟", updatedAt: 100, deletedAt: nil
        )
        try await dataRepo.merge(snapshot: FinanceDataSnapshot(
            transactions: [tx], categories: [category], budgets: []
        ))
        // 3) 软删：墓碑随 snapshot 保留。
        try await txnRepo.delete(id: "tx-a")
        let full = try await dataRepo.snapshot()
        guard full.transactions.contains(where: { $0.id == "tx-a" && $0.deletedAt != nil }) else {
            throw CheckError("软删墓碑未出现在快照中")
        }
        print("PASS 软删墓碑进入快照")

        // 4) v2 编解码往返（含墓碑字段）。
        let json = FinanceDataJsonCodec().encode(snapshot: full)
        guard json.contains("\"schema_version\": 2"), json.contains("\"deleted_at_epoch_millis\"") else {
            throw CheckError("导出缺少 schema v2/墓碑字段")
        }
        let decoded = try FinanceDataJsonCodec().decode(content: json)
        guard decoded.transactions.count == full.transactions.count else {
            throw CheckError("v2 往返丢失记录")
        }
        print("PASS v2 往返无损")

        // 5) 宽容导入去重 ID 与跨端向量一致。
        let csv = "交易时间,收/支,金额(元),交易对方,支付方式,商品\n1786350600000,支出,23.5,肯德基,微信支付,午饭套餐\n"
        let result = try TableTransactionImporter.shared.decodeCsvText(
            content: csv,
            categories: [],
            importedAtEpochMillis: 1_786_400_000_000
        )
        guard result.transactions.first?.id == "bill-7211b6506f333245" else {
            throw CheckError("去重 ID 与 shared 向量不一致：\(result.transactions.first?.id ?? "nil")")
        }
        print("PASS 去重 ID 跨端向量")

        // 6) v1 文档兼容读取：updatedAt 归 0、deletedAt 为空。
        let v1 = """
        {"format":"financeos-backup","schema_version":1,"transactions":[{"id":"t","amount_minor":1,"type":"EXPENSE","category_id":"system-food","date_time_epoch_millis":1786350600000}],"categories":[],"budgets":[]}
        """
        let legacy = try FinanceDataJsonCodec().decode(content: v1)
        guard legacy.transactions.first?.updatedAt == 0, legacy.transactions.first?.deletedAt == nil else {
            throw CheckError("v1 兼容读取元数据不符合预期")
        }
        print("PASS v1 兼容读取")

        print("FinanceOSShared macOS smoke checks passed ✔")
    }

    struct CheckError: LocalizedError {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }
}

final class CheckClock: NSObject, EpochClock {
    func nowMillis() -> Int64 { 1_786_400_000_000 }
}
