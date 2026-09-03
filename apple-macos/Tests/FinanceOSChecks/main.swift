import Foundation

// SwiftPM 可执行 target 的标准入口；顶层 await 需要 Swift 5.5+。
let smoke = Task { @MainActor in
    try await FinanceOSSmokeRunner.run()
}
try await smoke.value
