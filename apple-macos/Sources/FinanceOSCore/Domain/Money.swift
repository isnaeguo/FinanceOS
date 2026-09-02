import Foundation

/// 金额输入的整数部分最多允许的位数。
private let maxMajorDigits = 9
private let amountInputPattern = try! NSRegularExpression(
    pattern: "^(?:\\d{0,\(maxMajorDigits)})(?:\\.\\d{0,2})?$"
)

/// 过滤金额输入，只保留最多两位小数的十进制格式。
///
/// 逗号同时作为小数点接受，便于使用不同区域设置的数字键盘。
/// 返回 `nil` 表示输入非法，调用方应保持上一次的合法内容。
public func normalizeAmountInput(_ rawInput: String) -> String? {
    let normalized = rawInput.replacingOccurrences(of: ",", with: ".")
    if normalized.isEmpty { return normalized }
    let range = NSRange(normalized.startIndex..., in: normalized)
    return amountInputPattern.firstMatch(in: normalized, range: range) != nil ? normalized : nil
}

/// 将用户输入精确转换为最小货币单位，整个过程不经过 Double，避免二进制浮点误差。
public func parseAmountInMinorUnits(_ input: String, allowZero: Bool = false) -> Int64? {
    let trimmed = input.trimmingCharacters(in: .whitespaces)
    if trimmed.isEmpty || trimmed == "." { return nil }

    let parts = trimmed.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
    guard let major = Int64(parts[0].isEmpty ? "0" : String(parts[0])) else { return nil }
    let minor: Int64
    if parts.count > 1 {
        let padded = parts[1].padding(toLength: 2, withPad: "0", startingAt: 0)
        guard let value = Int64(padded.prefix(2)) else { return nil }
        minor = value
    } else {
        minor = 0
    }
    let (amount, didOverflow) = major.multipliedReportingOverflow(by: 100)
    if didOverflow { return nil }
    let (total, didUnderflow) = amount.addingReportingOverflow(minor)
    if didUnderflow { return nil }
    return total > 0 || (allowZero && total == 0) ? total : nil
}

/// 使用统一的人民币符号、千位分隔和两位小数展示金额，负数以减号开头。
public func formatMoney(_ amountMinor: Int64) -> String {
    let magnitude = abs(amountMinor)
    let major = String(magnitude / 100)
    var grouped = ""
    for (offset, character) in major.reversed().enumerated() {
        if offset > 0 && offset % 3 == 0 { grouped.insert(",", at: grouped.startIndex) }
        grouped.insert(character, at: grouped.startIndex)
    }
    let minor = String(format: "%02lld", magnitude % 100)
    return "\(amountMinor < 0 ? "-" : "")¥\(grouped).\(minor)"
}
