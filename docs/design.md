# Architecture

FinanceOS 使用平台原生 UI，并计划通过 Kotlin Multiplatform 共享业务逻辑。

## 模块

- `app`：Android Application。承载 Jetpack Compose UI、Android 生命周期和平台入口。
- `shared`：Kotlin Multiplatform Library。承载可跨端复用的领域模型与数据边界。

当前 `shared` 只启用 Android target。真正开始 iOS 客户端时再增加 iOS targets 和 SwiftUI 工程，避免提前维护无用配置。

## 分层约束

- `shared/domain`：纯 Kotlin 领域对象与业务规则。`Transaction` 是核心领域对象。
- `shared/data`：数据访问边界及后续的平台无关实现，不依赖 UI。
- `app`：Android UI 层，可依赖 `shared`；`shared` 不得依赖 Android UI。

v0.1 保持两模块结构，不提前拆分更多 Gradle 模块，也不引入导航、依赖注入、网络或云服务。
