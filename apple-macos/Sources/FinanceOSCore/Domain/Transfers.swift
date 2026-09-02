import Foundation

// MARK: - 数据快照

/// 可完整带走的 FinanceOS 业务数据快照，只包含跨平台领域模型。
public struct FinanceDataSnapshot: Equatable, Sendable {
    public init(
        transactions: [Transaction],
        categories: [Category],
        budgets: [Budget]
    ) {
        self.transactions = transactions
        self.categories = categories
        self.budgets = budgets
    }
    public var transactions: [Transaction]
    public var categories: [Category]
    public var budgets: [Budget]
}

/// 一次合并导入或完整恢复实际写入的数据数量。
public struct FinanceDataImportResult: Equatable, Sendable {
    public init(
        transactionCount: Int,
        categoryCount: Int,
        budgetCount: Int
    ) {
        self.transactionCount = transactionCount
        self.categoryCount = categoryCount
        self.budgetCount = budgetCount
    }
    public var transactionCount: Int
    public var categoryCount: Int
    public var budgetCount: Int
}

/// 导入文件无法被安全解释时抛出的可展示错误。
public struct DataTransferError: LocalizedError {
    public let message: String
    public var errorDescription: String? { message }
}

// MARK: - JSON 编解码

/// FinanceOS 版本化 JSON 快照编解码器，与 Android 端 `FinanceDataJsonCodec` 完全互通。
public enum FinanceDataJsonCodec {
    public static let backupFormat = "financeos-backup"
    public static let currentSchemaVersion = 1

    /// 输出稳定、可读且不含平台存储细节的 JSON。
    public static func encode(_ snapshot: FinanceDataSnapshot) throws -> String {
        let document = BackupDocument(
            transactions: snapshot.transactions.sorted { $0.id < $1.id }.map(TransactionDocument.init),
            categories: snapshot.categories.sorted { $0.id < $1.id }.map(CategoryDocument.init),
            budgets: snapshot.budgets.sorted { $0.id < $1.id }.map(BudgetDocument.init)
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(document)
        return String(decoding: data, as: UTF8.self)
    }

    /// 只接受当前支持的 FinanceOS 备份版本，避免错误解释未来格式。
    public static func decode(_ content: String) throws -> FinanceDataSnapshot {
        let cleaned = content.hasPrefix("\u{FEFF}") ? String(content.dropFirst()) : content
        guard let data = cleaned.data(using: .utf8) else {
            throw DataTransferError(message: "JSON 数据格式不正确。")
        }
        let decoder = JSONDecoder()
        do {
            let document = try decoder.decode(BackupDocument.self, from: data)
            guard document.format == backupFormat else {
                throw DataTransferError(message: "不是 FinanceOS 数据文件。")
            }
            guard document.schemaVersion == currentSchemaVersion else {
                throw DataTransferError(message: "暂不支持此备份版本：\(document.schemaVersion)。")
            }
            return FinanceDataSnapshot(
                transactions: try document.transactions.map { try $0.toDomain() },
                categories: try document.categories.map { try $0.toDomain() },
                budgets: try document.budgets.map { try $0.toDomain() }
            )
        } catch let error as DataTransferError {
            throw error
        } catch {
            throw DataTransferError(message: decodeErrorMessage(from: error))
        }
    }

    public static func decodeErrorMessage(from error: Error) -> String {
        let description = error.localizedDescription
        if description.contains("amount_minor") { return "流水金额字段 amount_minor 格式不正确。" }
        if description.contains("date_time_epoch_millis") { return "时间字段 date_time_epoch_millis 格式不正确。" }
        if description.contains("schema_version") { return "JSON 缺少 schema_version 字段。" }
        if description.contains("format") { return "JSON 缺少 format 字段。" }
        return "JSON 数据格式不正确。"
    }
}

private struct BackupDocument: Codable {
    var format: String = FinanceDataJsonCodec.backupFormat
    var schemaVersion: Int = FinanceDataJsonCodec.currentSchemaVersion
    var transactions: [TransactionDocument]
    var categories: [CategoryDocument]
    var budgets: [BudgetDocument]

    enum CodingKeys: String, CodingKey {
        case format
        case schemaVersion = "schema_version"
        case transactions
        case categories
        case budgets
    }
}

private struct TransactionDocument: Codable {
    var id: String
    var amountMinor: Int64
    var type: String
    var categoryId: String
    var accountId: String?
    var dateTimeEpochMillis: Int64
    var note: String?

    enum CodingKeys: String, CodingKey {
        case id
        case amountMinor = "amount_minor"
        case type
        case categoryId = "category_id"
        case accountId = "account_id"
        case dateTimeEpochMillis = "date_time_epoch_millis"
        case note
    }

    init(_ transaction: Transaction) {
        id = transaction.id
        amountMinor = transaction.amount
        type = transaction.type.rawValue
        categoryId = transaction.categoryId
        accountId = transaction.accountId
        dateTimeEpochMillis = Int64((transaction.dateTime.timeIntervalSince1970 * 1000).rounded())
        note = transaction.note
    }

    func toDomain() throws -> Transaction {
        guard let transactionType = TransactionType(rawValue: type) else {
            throw DataTransferError(message: "流水类型无效：\(type)。")
        }
        return Transaction(
            id: id,
            amount: amountMinor,
            type: transactionType,
            categoryId: categoryId,
            accountId: accountId,
            dateTime: Date(timeIntervalSince1970: Double(dateTimeEpochMillis) / 1000),
            note: note
        )
    }
}

private struct CategoryDocument: Codable {
    var id: String
    var name: String
    var type: String
    var iconKey: String
    var isSystem: Bool

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case type
        case iconKey = "icon_key"
        case isSystem = "is_system"
    }

    init(_ category: Category) {
        id = category.id
        name = category.name
        type = category.type.rawValue
        iconKey = category.iconKey
        isSystem = category.isSystem
    }

    func toDomain() throws -> Category {
        guard let categoryType = CategoryType(rawValue: type) else {
            throw DataTransferError(message: "分类类型无效：\(type)。")
        }
        return Category(id: id, name: name, type: categoryType, iconKey: iconKey, isSystem: isSystem)
    }
}

private struct BudgetDocument: Codable {
    var id: String
    var year: Int
    var month: Int
    var categoryId: String?
    var amountLimitMinor: Int64

    enum CodingKeys: String, CodingKey {
        case id
        case year
        case month
        case categoryId = "category_id"
        case amountLimitMinor = "amount_limit_minor"
    }

    init(_ budget: Budget) {
        id = budget.id
        year = budget.month.year
        month = budget.month.month
        categoryId = budget.categoryId
        amountLimitMinor = budget.amountLimit
    }

    func toDomain() throws -> Budget {
        guard year > 0 else { throw DataTransferError(message: "预算年份无效：\(year)。") }
        guard (1...12).contains(month) else { throw DataTransferError(message: "预算月份无效：\(month)。") }
        return Budget(id: id, month: BudgetMonth(year: year, month: month), amountLimit: amountLimitMinor, categoryId: categoryId)
    }
}

// MARK: - CSV 编解码

/// 流水 CSV 编解码器，金额以最小货币单位列作为无损导入依据。
public enum TransactionCsvCodec {
    public static let headers = [
        "id",
        "amount_minor",
        "amount",
        "type",
        "category_id",
        "account_id",
        "date_time_epoch_millis",
        "note",
    ]

    public static func encode(_ transactions: [Transaction]) -> String {
        var output = "\u{FEFF}"
        output += headers.joined(separator: ",") + "\n"
        let sorted = transactions.sorted { lhs, rhs in
            if lhs.dateTime != rhs.dateTime { return lhs.dateTime > rhs.dateTime }
            return lhs.id < rhs.id
        }
        for transaction in sorted {
            let fields = [
                transaction.id,
                String(transaction.amount),
                formatMajorAmount(transaction.amount),
                transaction.type.rawValue,
                transaction.categoryId,
                transaction.accountId ?? "",
                String(Int64((transaction.dateTime.timeIntervalSince1970 * 1000).rounded())),
                transaction.note ?? "",
            ]
            output += fields.map(escapeField).joined(separator: ",") + "\n"
        }
        return output
    }

    public static func decode(_ content: String) throws -> [Transaction] {
        let cleaned = content.hasPrefix("\u{FEFF}") ? String(content.dropFirst()) : content
        let rows = try parseRows(cleaned)
        if rows.isEmpty { throw DataTransferError(message: "CSV 文件为空。") }

        let header = rows[0]
        var indices: [String: Int] = [:]
        for requiredHeader in headers {
            guard let index = header.firstIndex(of: requiredHeader) else {
                throw DataTransferError(message: "CSV 缺少字段：\(requiredHeader)。")
            }
            indices[requiredHeader] = index
        }

        var transactions: [Transaction] = []
        for (offset, row) in rows.dropFirst().enumerated() where !row.allSatisfy({ $0.trimmingCharacters(in: .whitespaces).isEmpty }) {
            let lineNumber = offset + 2
            func field(_ name: String) -> String {
                guard let index = indices[name], index < row.count else { return "" }
                return row[index]
            }
            do {
                guard let amount = Int64(field("amount_minor")) else {
                    throw DataTransferError(message: "金额字段 amount_minor 无效。")
                }
                guard let epochMillis = Int64(field("date_time_epoch_millis")) else {
                    throw DataTransferError(message: "时间字段无效。")
                }
                guard let type = TransactionType(rawValue: field("type")) else {
                    throw DataTransferError(message: "流水类型无效：\(field("type"))。")
                }
                transactions.append(Transaction(
                    id: field("id"),
                    amount: amount,
                    type: type,
                    categoryId: field("category_id"),
                    accountId: field("account_id").isEmpty ? nil : field("account_id"),
                    dateTime: Date(timeIntervalSince1970: Double(epochMillis) / 1000),
                    note: field("note").isEmpty ? nil : field("note")
                ))
            } catch let error as DataTransferError {
                throw DataTransferError(message: "CSV 第 \(lineNumber) 行数据无效：\(error.message)")
            } catch {
                throw DataTransferError(message: "CSV 第 \(lineNumber) 行数据无效：字段格式错误")
            }
        }
        return transactions
    }

    public static func escapeField(_ value: String) -> String {
        let needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
        return needsQuotes ? "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\"" : value
    }

    public static func formatMajorAmount(_ amountMinor: Int64) -> String {
        String(abs(amountMinor) / 100) + "." + String(format: "%02d", abs(amountMinor) % 100)
    }

    /// 按 RFC 4180 的核心转义规则解析，支持备注中的逗号、引号和换行。
    public static func parseRows(_ content: String) throws -> [[String]] {
        var rows: [[String]] = []
        var row: [String] = []
        var field = ""
        var inQuotes = false
        var index = content.startIndex

        func finishField() {
            row.append(field)
            field = ""
        }

        func finishRow() {
            finishField()
            rows.append(row)
            row = []
        }

        while index < content.endIndex {
            let character = content[index]
            let next = content.index(after: index)
            switch character {
            case "\"":
                if inQuotes && next < content.endIndex && content[next] == "\"" {
                    field.append("\"")
                    index = next
                } else if inQuotes {
                    inQuotes = false
                } else if field.isEmpty {
                    inQuotes = true
                } else {
                    throw DataTransferError(message: "CSV 引号位置无效。")
                }
            case ",":
                if inQuotes { field.append(character) } else { finishField() }
            case "\n":
                if inQuotes { field.append(character) } else { finishRow() }
            case "\r":
                if inQuotes {
                    field.append(character)
                } else if next < content.endIndex && content[next] == "\n" {
                    index = next
                    finishRow()
                } else {
                    finishRow()
                }
            default:
                field.append(character)
            }
            index = content.index(after: index)
        }

        if inQuotes { throw DataTransferError(message: "CSV 存在未闭合的引号。") }
        if !field.isEmpty || !row.isEmpty { finishRow() }
        return rows
    }
}
