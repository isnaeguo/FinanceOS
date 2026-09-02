# FinanceOS
一个个人财务管理系统

# 模块结构

- `app/` Android（Compose）界面
- `shared/` Kotlin Multiplatform 领域层（jvm/android，已启用 iOS target 准备）
- `apple-macos/` macOS 原生工程（Swift Package：FinanceOSCore 纯 Swift 领域层 + FinanceOSMac SwiftUI/Liquid Glass + WidgetKit 小组件与打包脚本；与 Android/shared 数据与导入/导出格式互通）
- `ios/` iOS 准备说明（KMP shared Framework 或复用 FinanceOSCore）

## macOS / iOS 构建提示

```sh
# macOS debug（含小组件）
cd apple-macos && ./scripts/make-app-debug.sh && open dist-debug/FinanceOS.app
# macOS 领域校验
cd apple-macos && swift run FinanceOSChecks
# iOS：见 ios/README.md（需 Xcode 环境）
```
