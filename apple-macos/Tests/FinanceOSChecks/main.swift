import Foundation
import FinanceOSCore

// SwiftPM 可执行 target 的标准入口：SwiftPM 会为名为 main.swift 的文件自动生成入口。
// 所有校验逻辑都放在 FinanceOSCheckRunner 中，避免顶层代码的类型推断限制。
FinanceOSCheckRunner.run()
