#if os(macOS)
import Foundation
import CoreFoundation

/// 从 .xlsx 读取第一个工作表并按“行”原样还原为 CSV 文本。
///
/// 还原的 CSV 仍保留文件原始的行顺序（含顶部说明行），随后交给
/// FlexibleSpreadsheetImporter 完成表头自动定位、跳过、分类与 ID 处理，
/// 因此与直接导入 CSV 行为完全一致（不转置、不丢行）。
public enum XlsxImportReader {
    private static let zipMagicPK: UInt32 = 0x504b0304

    public static func isXlsx(_ data: Data) -> Bool {
        guard data.count >= 4 else { return false }
        let bytes = [UInt8](data.prefix(4))
        return bytes[0] == 0x50 && bytes[1] == 0x4B && bytes[2] == 0x03 && bytes[3] == 0x04
    }

    /// 解压并读取第一张工作表，返回按行组织的网格（行 = 数组，单元格顺序即列顺序）。
    public static func readFirstSheet(_ data: Data) throws -> [[String]] {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("financeos-xlsx-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let zip = directory.appendingPathComponent("book.zip")
        try data.write(to: zip)

        // 用系统 ditto 解压（macOS 自带，支持 zip）
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        process.arguments = ["-x", "-k", zip.path, directory.path]
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw DataTransferError(message: "所选文件不是有效的 XLSX。")
        }

        func text(_ name: String) -> String? {
            let url = directory.appendingPathComponent(name)
            return try? String(contentsOf: url, encoding: .utf8)
        }

        let sharedStrings = parseSharedStrings(text("xl/sharedStrings.xml"))
        let sheetPath = resolveFirstSheetPath(
            workbook: text("xl/workbook.xml"),
            rels: text("xl/_rels/workbook.xml.rels")
        )
        guard let sheet = text(sheetPath) else {
            throw DataTransferError(message: "XLSX 中没有可读取的工作表。")
        }
        return parseSheet(sheet, sharedStrings: sharedStrings)
    }

    /// 把工作表网格还原成 CSV 文本（原始行顺序，字段按 RFC 风格转义）。
    public static func gridToCSV(_ grid: [[String]]) -> String {
        grid.map { row in
            row.map(escape).joined(separator: ",")
        }.joined(separator: "\n")
    }

    private static func escape(_ value: String) -> String {
        let needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
        return needsQuotes ? "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\"" : value
    }

    // MARK: - 按行解析（与跨端校验一致的实现）

    private static func parseSharedStrings(_ data: String?) -> [String] {
        guard let data else { return [] }
        let si = regex(#"<si>(.*?)</si>"#)
        let tags = regex(#"<[^>]+>"#)
        return matches(si, in: data).map { match -> String in
            let body = match
            let stripped = tags.stringByReplacingMatches(in: body, range: NSRange(body.startIndex..., in: body), withTemplate: "")
            return decodeEntities(stripped)
        }
    }

    private static func decodeEntities(_ text: String) -> String {
        text
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
    }

    private static func resolveFirstSheetPath(workbook: String?, rels: String?) -> String {
        if let workbook, let rels {
            let sheetPattern = regex(#"<sheet[^>]*r:id="([^"]+)"[^>]*/?>"#)
            if let sheet = firstCapture(sheetPattern, in: workbook) {
                let relPattern = regex(#"<Relationship[^>]*Id="([^"]*)"[^>]*Target="([^"]*)"[^>]*/?>"#)
                for (start, captures) in captures(relPattern, in: rels) {
                    if captures.first == sheet {
                        var target = captures.count > 1 ? captures[1] : ""
                        if target.hasPrefix("/") {
                            target = String(target.dropFirst())
                        }
                        return target.hasPrefix("xl/") ? target : "xl/\(target)"
                    }
                }
            }
        }
        return "xl/worksheets/sheet1.xml"
    }

    /// 返回网格；每一行 = [单元格文本]，列顺序 = 从左到右，空列补 ""。
    private static func parseSheet(_ sheet: String, sharedStrings: [String]) -> [[String]] {
        let rowPattern = regex(#"<row r="\d+"[^>]*>(.*?)</row>"#)
        let cellPattern = regex(#"<c\b[^>]*?(?:/>|>.*?</c>)"#)
        let refPattern = regex(#"<c r="([A-Z]+)\d+""#)
        let typePattern = regex(#"\st="([^"]*)""#)
        let valuePattern = regex(#"<v>(.*?)</v>"#)

        var grid: [[String]] = []
        for (rowXml, _) in captures(rowPattern, in: sheet) {
            var cells: [Int: String] = [:]
            var maxColumn = -1
            for cell in matches(cellPattern, in: rowXml) {
                guard let ref = firstCapture(refPattern, in: cell) else { continue }
                let type = firstCapture(typePattern, in: cell) ?? ""
                let raw = firstCapture(valuePattern, in: cell) ?? ""
                var column = 0
                for scalar in ref.unicodeScalars {
                    column = column * 26 + Int(scalar.value - 65) + 1
                }
                column -= 1
                maxColumn = max(maxColumn, column)
                let value: String
                if type == "s" {
                    if let index = Int(raw), sharedStrings.indices.contains(index) {
                        value = sharedStrings[index]
                    } else {
                        value = ""
                    }
                } else {
                    value = raw
                }
                cells[column] = value
            }
            if !cells.isEmpty {
                var row: [String] = []
                for column in 0...maxColumn {
                    row.append(cells[column] ?? "")
                }
                if row.contains(where: { !$0.isEmpty }) {
                    grid.append(row)
                }
            }
        }
        return grid
    }


    // MARK: - 正则辅助

    private static func regex(_ pattern: String) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators])
    }

    private static func matches(_ expression: NSRegularExpression, in text: String) -> [String] {
        let range = NSRange(text.startIndex..., in: text)
        return expression.matches(in: text, range: range).map { match in
            String(text[Range(match.range, in: text)!])
        }
    }

    private static func firstCapture(_ expression: NSRegularExpression, in text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = expression.firstMatch(in: text, range: range),
              match.numberOfRanges > 1,
              match.range(at: 1).location != NSNotFound else { return nil }
        return String(text[Range(match.range(at: 1), in: text)!])
    }

    private static func secondCapture(_ expression: NSRegularExpression, in text: String) -> String? {
        capture(at: 2, expression: expression, in: text)
    }

    private static func thirdCapture(_ expression: NSRegularExpression, in text: String) -> String? {
        capture(at: 3, expression: expression, in: text)
    }

    private static func capture(at index: Int, expression: NSRegularExpression, in text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = expression.firstMatch(in: text, range: range),
              expression.numberOfCaptureGroups >= index,
              match.range(at: index).location != NSNotFound else { return nil }
        return String(text[Range(match.range(at: index), in: text)!])
    }

    /// 返回 (整段, 捕获组列表) 的元组列表，供多组捕获遍历。
    private static func captures(_ expression: NSRegularExpression, in text: String) -> [(String, [String])] {
        let range = NSRange(text.startIndex..., in: text)
        return expression.matches(in: text, range: range).map { match -> (String, [String]) in
            let full = String(text[Range(match.range, in: text)!])
            var groups: [String] = []
            for index in 1..<match.numberOfRanges where match.range(at: index).location != NSNotFound {
                groups.append(String(text[Range(match.range(at: index), in: text)!]))
            }
            return (full, groups)
        }
    }
}

#endif
