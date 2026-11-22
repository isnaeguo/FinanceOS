# FinanceOS macOS Xcode 工程

用 Xcode 打开 `FinanceOS.xcodeproj`，选择 FinanceOS scheme 和 My Mac。构建时先由 Widget target 调用 Gradle 生成匹配 Debug 或 Release 的 FinanceOSShared 静态框架，App 与 Widget 都链接该框架。

## App 与小组件签名

1. 为 FinanceOS 和 FinanceOSWidget 选择同一个签名团队。
2. 检查两端的 App Groups 能力包含 `group.com.financeos.ios`，以访问同一个 Room 数据库。
3. 构建并运行 App。安装签名构建后，可从 macOS 的「编辑小组件」添加 FinanceOS 本月概览。

App 沙盒声明用户选择文件的读写权限及局域网连接和接收权限。命令行脚本可生成本地构建。要分发签名 App 与 Widget，在 Xcode 中完成配套签名或归档。
