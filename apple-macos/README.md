# FinanceOS for macOS

原生 SwiftUI 应用，支持 macOS 26 和 Apple Silicon。App 与小组件通过 FinanceOSShared 静态框架复用 Kotlin Multiplatform 内核。

- 使用侧边栏查看总览、流水、预算、AI 账单助手、局域网共享和数据备份。按 ⌘N 快速记账。
- 在「数据与备份」中导入账单文件或粘贴表格。可调整列、核对重复和异常项，并为单一外币账单填写汇率后确认入账。
- 在「AI 账单助手」中设置兼容 OpenAI Responses API 的 HTTPS 地址、模型 ID 及服务要求的密钥。先选月份，再生成分类建议或分析消费。

## 构建

需要完整 Xcode、macOS 26 SDK 和 JDK 21。从仓库根目录运行：

```sh
./apple-macos/scripts/make-app-debug.sh
```

调试 App 位于 `apple-macos/dist-debug/FinanceOS.app`。需要启用 App Group 和使用小组件时，请参照 [Xcode 签名说明](../apple-xcode/README.md)。

共享框架及 Swift 调用可通过 `apple-macos/scripts/make-xcframework.sh` 和在 `apple-macos` 中运行 `swift run FinanceOSChecks` 验证。

账本的实际数据库位置可在「数据与备份」查看。具有权限的签名构建优先访问 App Group。已有 `store.json` 会合并入 Room，数据库提交成功后再重命名原文件。
