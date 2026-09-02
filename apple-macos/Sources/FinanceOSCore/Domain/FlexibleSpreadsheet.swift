import Foundation
import CoreFoundation

/// 面向用户常见表格与微信/支付宝账单的宽容流水导入（CSV 文本）。
///
/// 与 Android 端 TableTransactionImporter 语义一致：
/// - 表头支持 FinanceOS 导出列名、常用中文别名与账单列（交易时间/收/支/金额(元)/支付方式/
///   商品(说明)/交易对方/备注…），逗号或 Tab 分隔，UTF-8/GB18030 由调用方解码后传入；
/// - id 可省略（自动生成）；金额可给“元”或“分”，负数取绝对值；
/// - 日期支持 Unix 毫秒、Excel 序列日期、文本日期；
/// - 没有可识别“分类”时默认归入“其他”（system-other）；
/// - 不计收支/退款/空收支行自动跳过；CSV 引号宽容解析。
/// 输出为标准 FinanceOS CSV 文本，交给 TransactionCsvCodec 完成按 ID 合并。
public enum FlexibleSpreadsheetImporter {
    /// 宽容解析并输出标准 FinanceOS CSV。
    public static func normalizeCSV(_ content: String, categories: [Category]) throws -> String {
        let grid = try parseRowsTolerant(content)
        guard !grid.isEmpty else { throw DataTransferError(message: "所选文件没有可导入的数据。") }

        // 微信/支付宝账单顶部常有多行说明（“微信支付账单明细”“导出时间…”），
        // 真正表头不在第一行：自动扫描，找第一行能识别出 >=2 个已知列名的行。
        func recognizedCount(_ row: [String]) -> Int {
            row.reduce(0) { $0 + (canonicalColumn($1) != nil ? 1 : 0) }
        }
        let strictIndex = grid.firstIndex { recognizedCount($0) >= 2 }
        let headerIndex = strictIndex ?? grid.firstIndex { recognizedCount($0) >= 1 }
        guard let headerIndex else {
            throw DataTransferError(message: "没有找到可识别的表头。请使用“交易时间/金额/收/支/分类/备注”等列名，并确保第一列是列名。")
        }
        let headers = grid[headerIndex].map(canonicalColumn)
        let dataStart = headerIndex + 1
        guard dataStart < grid.count else {
            throw DataTransferError(message: "所选文件只包含表头，没有流水数据。")
        }
        var columnIndex: [String: Int] = [:]
        for (index, canonical) in headers.enumerated() {
            if let canonical, columnIndex[canonical] == nil {
                columnIndex[canonical] = index
            }
        }
        func value(_ row: [String], _ key: String) -> String {
            guard let index = columnIndex[key], index < row.count else { return "" }
            return row[index].trimmingCharacters(in: .whitespacesAndNewlines)
        }

        let categoryById = Dictionary(uniqueKeysWithValues: categories.map { ($0.id, $0) })
        let categoryByName = Dictionary(uniqueKeysWithValues: categories.map { ($0.name, $0) })

        var errors: [String] = []
        var records: [[String]] = []
        var skipped = 0
        var skipSamples: [String] = []

        for (offset, rawRow) in grid.dropFirst(dataStart).enumerated() where rawRow.contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
            let line = dataStart + offset + 1
            do {
                // 状态列为 退款/失败/关闭/撤销 时整行跳过（收/支列可能仍显示支出）。
                let statusRaw = value(rawRow, "status")
                if !statusRaw.isEmpty
                    && (statusRaw.contains("退款") || statusRaw.contains("失败")
                        || statusRaw.contains("关闭") || statusRaw.contains("撤销") || statusRaw.contains("未支付")) {
                    skipped += 1
                    if skipSamples.count < 3 { skipSamples.append("（空）/\(statusRaw.isEmpty ? "（空）" : statusRaw)") }
                    continue
                }
                let typeRaw = value(rawRow, "type")
                // 不计收支 / 退款 / 空收支 行跳过
                if typeRaw.isEmpty || typeRaw == "/" || typeRaw.contains("不计") || typeRaw.contains("退款") {
                    skipped += 1
                    if skipSamples.count < 3 { skipSamples.append("\(typeRaw.isEmpty ? "（空）" : typeRaw)/\(statusRaw.isEmpty ? "（空）" : statusRaw)") }
                    continue
                }
                let type: String
                if typeRaw.contains("收入") || typeRaw.uppercased() == "INCOME" {
                    type = "INCOME"
                } else if typeRaw.contains("支出") || typeRaw.uppercased() == "EXPENSE" {
                    type = "EXPENSE"
                } else if typeRaw.contains("收") {
                    type = "INCOME"
                } else if typeRaw.contains("支") {
                    type = "EXPENSE"
                } else {
                    throw DataTransferError(message: "第 \(line) 行“收/支”无法识别：\(typeRaw)")
                }

                let minor = try resolveAmountMinor(value(rawRow, "amount_minor"), value(rawRow, "amount"), line)
                if minor == 0 {
                    // 0 元交易（如优惠券抵扣、0 元单）不产生收支，跳过
                    skipped += 1
                    continue
                }
                if minor < 45 {
                    // 屏蔽小于 0.45 元的小额流水
                    skipped += 1
                    continue
                }

                // 分类只作为可选项：能匹配到本机分类名/ID 才用，其余一律归“其他”，绝不因分类拒绝导入。
                let categoryInput = value(rawRow, "category_id")
                let categoryId = categoryById[categoryInput]?.id
                    ?? categoryByName[categoryInput]?.id
                    ?? "system-other"

                let dateRaw = value(rawRow, "date")
                guard !dateRaw.isEmpty else { throw DataTransferError(message: "第 \(line) 行缺少“日期”") }
                let millis = try resolveDateMillis(dateRaw, line: line)

                // 名称只取“交易对方”：账单中只有这一项是真实有效的收款/付款方信息；
                // 支付方式保留在“账户”列。占位符（/ - 空 等）视为无名称。
                let counterparty = value(rawRow, "counterparty")
                let fullNote = isPlaceholder(counterparty) ? "" : counterparty

                let id = value(rawRow, "id").isEmpty
                    ? stableRowId(orderId: value(rawRow, "order_id"), dateMillis: millis, amountMinor: minor, typeRaw: type, note: fullNote, counterparty: counterparty)
                    : value(rawRow, "id")
                records.append([
                    id,
                    String(minor),
                    formatMajor(minor),
                    type,
                    categoryId,
                    isPlaceholder(value(rawRow, "account_id")) ? "" : value(rawRow, "account_id"),
                    String(millis),
                    fullNote,
                ])
            } catch let error as DataTransferError {
                errors.append(error.message)
            }
        }

        if records.isEmpty {
            if skipped > 0 {
                let samples = skipSamples.isEmpty ? "" : "；示例（收/支/状态）：" + skipSamples.joined(separator: "，")
                throw DataTransferError(message: "未导入任何流水：全部 \(skipped) 行被跳过（不计收支/退款/小额）。\(samples)")
            }
            throw DataTransferError(message: errors.isEmpty ? "所选文件没有可导入的流水。" : errors.joined(separator: "；"))
        }
        if !errors.isEmpty {
            throw DataTransferError(message: errors.joined(separator: "；"))
        }
        return buildStandardCSV(records)
    }

    // MARK: - 标准 CSV 输出

    private static func buildStandardCSV(_ records: [[String]]) -> String {
        let header = ["id", "amount_minor", "amount", "type", "category_id", "account_id", "date_time_epoch_millis", "note"]
        var output = "\u{FEFF}"
        output += header.joined(separator: ",") + "\n"
        for record in records {
            output += record.map(escape).joined(separator: ",") + "\n"
        }
        return output
    }

    private static func escape(_ value: String) -> String {
        let needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
        return needsQuotes ? "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\"" : value
    }


    // 生成稳定行 ID 用于去重：优先业务订单号；否则由时间/金额/方向/内容指纹派生（本端内确定）。
    private static func stableRowId(orderId: String, dateMillis: Int64, amountMinor: Int64, typeRaw: String, note: String, counterparty: String) -> String {
        let clean: (String) -> String = { $0.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\\s+", with: "", options: .regularExpression) }
        if !orderId.isEmpty {
            return "bill-" + String(clean(orderId).prefix(64))
        }
        let notePart = clean(note).prefix(48)
        let cpPart = clean(counterparty).prefix(24)
        let body = "\(dateMillis)|\(amountMinor)|\(typeRaw.contains("INCOME") ? "in" : "out")|\(notePart)|\(cpPart)"
        var hash: UInt64 = 1469598103934665603
        for byte in body.utf8 {
            hash ^= UInt64(byte)
            hash &*= 1099511628211
        }
        return "bill-" + String(hash, radix: 16)
    }

    // MARK: - 表头别名

    private static let aliases: [String: [String]] = [
        "id": ["id", "流水id", "编号"],
        "amount_minor": ["amount_minor", "金额分", "金额(分)", "金额(最小单位)"],
        "amount": ["amount", "金额", "金额元", "金额(元)"],
        "type": ["type", "类型", "收支", "收支类型", "收/支", "类别"],
        "category_id": ["category_id", "分类id", "分类", "分类名称", "分类编号", "标签"],
        "account_id": ["account_id", "账户", "账户id", "账号", "银行卡", "支付方式", "收付款方式", "付款方式"],
        "date": ["date", "时间", "日期", "日期时间", "记账时间", "交易时间", "时间戳", "date_time_epoch_millis"],
        "status": ["状态", "交易状态", "当前状态", "支付状态", "订单状态", "状态说明"],
        "note": ["note", "备注", "描述"],
        "product": ["商品", "商品说明", "商品名称", "商品描述"],
        "counterparty": ["交易对方", "对方", "对方账号", "收款方", "付款方"],
    ]

    private static func canonicalColumn(_ raw: String) -> String? {
        let key = String(raw.trimmingCharacters(in: .whitespaces).lowercased().filter { $0.isLetter || $0.isNumber })
        if key.isEmpty { return nil }
        for (canonical, variants) in aliases where variants.contains(key) {
            return canonical
        }
        return nil
    }


    private static func isPlaceholder(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty || trimmed == "/" || trimmed == "-" || trimmed == "—" ||
            trimmed == "无" || trimmed == "暂无"
    }

    // MARK: - 金额 / 日期

    private static func resolveAmountMinor(_ minorRaw: String, _ majorRaw: String, _ line: Int) throws -> Int64 {
        let cleanMinor = minorRaw.filter { $0.isNumber || $0 == "-" }
        if !cleanMinor.isEmpty && cleanMinor != "-" {
            guard let minor = Int64(cleanMinor) else {
                throw DataTransferError(message: "第 \(line) 行“金额(分)”无法解析：\(minorRaw)")
            }
            return minor < 0 ? -minor : minor
        }
        let cleaned = majorRaw
            .replacingOccurrences(of: "，", with: ",")
            .replacingOccurrences(of: "￥", with: "")
            .replacingOccurrences(of: "¥", with: "")
            .replacingOccurrences(of: ",", with: "")
            .replacingOccurrences(of: "+", with: "")
        guard let major = Decimal(string: cleaned) else {
            throw DataTransferError(message: "第 \(line) 行“金额”无法解析：\(majorRaw)")
        }
        var scaled = major * 100
        var rounded = Decimal()
        NSDecimalRound(&rounded, &scaled, 0, .plain)
        var value = NSDecimalNumber(decimal: rounded).int64Value
        if value < 0 { value = -value }
        return value
    }

    private static func formatMajor(_ minor: Int64) -> String {
        String(format: "%.2f", Double(minor) / 100)
    }

    private static func resolveDateMillis(_ raw: String, line: Int) throws -> Int64 {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { throw DataTransferError(message: "第 \(line) 行“日期”为空") }
        let digits = text.filter { $0.isNumber || $0 == "." || $0 == "-" }
        if !digits.isEmpty, digits.allSatisfy({ $0.isNumber || $0 == "." || $0 == "-" }), let number = Double(digits) {
            // 大于 1e11 视为 Unix 毫秒，否则按 Excel 序列日期。
            if number >= 100_000_000_000 {
                return Int64(number.rounded())
            }
            return excelSerialToMillis(number)
        }
        let normalized = text.replacingOccurrences(of: "T", with: " ")
        for pattern in textDatePatterns {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.timeZone = .current
            formatter.dateFormat = pattern
            if let date = formatter.date(from: normalized) {
                return Int64(date.timeIntervalSince1970 * 1000)
            }
        }
        if let millis = Int64(text), millis > 100_000_000_000 {
            return millis
        }
        throw DataTransferError(message: "第 \(line) 行“日期”无法识别：\(text)")
    }

    private static let textDatePatterns = [
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/M/d HH:mm:ss",
        "yyyy/M/d HH:mm",
        "yyyy/M/d",
    ]

    private static func excelSerialToMillis(_ serial: Double) -> Int64 {
        // Excel 日期系统零点 1899-12-30（兼容 1900 闰年 bug）
        let wholeDays = floor(serial)
        let fraction = serial - wholeDays
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let base = calendar.date(from: DateComponents(year: 1899, month: 12, day: 30))!
        let seconds = fraction * 86_400
        let date = base.addingTimeInterval(wholeDays * 86_400 + seconds)
        return Int64(date.timeIntervalSince1970 * 1000)
    }


    // MARK: - 宽容 CSV 解析（逗号或 Tab 分隔）

    private static func parseRowsTolerant(_ content: String) -> [[String]] {
        let text = content.hasPrefix("\u{FEFF}") ? String(content.dropFirst()) : content
        // 按物理行解析，每行独立；账单类文件没有跨行引号字段，可彻底避免状态串扰。
        let lines = text.components(separatedBy: .newlines)
        let firstLine = lines.first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) ?? ""
        let tabs = firstLine.reduce(0) { $0 + ($1 == "\t" ? 1 : 0) }
        let commas = firstLine.reduce(0) { $0 + ($1 == "," ? 1 : 0) }
        let delimiter: Character = tabs > commas ? "\t" : ","

        var grid: [[String]] = []
        for rawLine in lines {
            let line = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
            if line.trimmingCharacters(in: .whitespaces).isEmpty { continue }
            let row = parseLine(line, delimiter: delimiter)
            if row.contains(where: { !$0.isEmpty }) {
                grid.append(row)
            }
        }
        return grid
    }

    private static func parseLine(_ line: String, delimiter: Character) -> [String] {
        var row: [String] = []
        var field = ""
        var inQuotes = false
        var characters = Array(line)
        var index = 0
        while index < characters.count {
            let character = characters[index]
            switch character {
            case "\"":
                if inQuotes, index + 1 < characters.count, characters[index + 1] == "\"" {
                    field.append("\"")
                    index += 1
                } else if inQuotes {
                    inQuotes = false
                } else if field.isEmpty {
                    inQuotes = true
                } else {
                    // 宽容：字段中间的引号按普通字符处理
                    field.append("\"")
                }
            case delimiter:
                if inQuotes { field.append(character) } else { row.append(field); field = "" }
            default:
                field.append(character)
            }
            index += 1
        }
        row.append(field)
        return row
    }

    /// CSV 文本解码：优先 UTF-8，失败回退 GB18030（微信/支付宝导出常用）。
    public static func decodeSpreadsheetText(_ data: Data) -> String {
        if let utf8 = String(data: data, encoding: .utf8), !utf8.contains("\u{FFFD}") {
            return utf8
        }
        let gb18030 = CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)
        )
        if let decoded = String(data: data, encoding: String.Encoding(rawValue: gb18030)) {
            return decoded
        }
        return String(data: data, encoding: .utf8) ?? ""
    }
}

