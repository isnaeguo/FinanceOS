# iOS 准备（FinanceOS iOS）

当前 iOS 侧处于“代码就绪、等待 Xcode 工程”阶段，两条路径可复用同一套业务逻辑：

## 方案 A：KMP shared（与 Android 同一套 Kotlin 逻辑，推荐主路径）
- `shared/` 已启用 Apple targets：`iosArm64()`（真机）、`iosSimulatorArm64()`（模拟器），
  并各自导出静态 Framework：`FinanceOSShared.framework`。
- 在装有 Xcode 的 Mac 上执行：
  ```sh
  cd shared && ../gradlew :shared:linkDebugFrameworkIosSimulatorArm64
  ```
  产物在 `shared/build/bin/iosSimulatorArm64/debugFramework/FinanceOSShared.framework`，
  随后新建 iOS App 工程把该 Framework 拖入并桥接（Kotlin/Native 会生成可直接调用的 ObjC 头）。
- 说明：Room KMP（androidx.room3-runtime + sqlite-bundled）与 kotlinx.serialization/coroutines
  均支持 iOS target；首次 iOS 构建会下载 Kotlin/Native 工具链（需网络）。
- 建议 iOS UI 采用 SwiftUI，数据层调用 shared 导出的 Kotlin API（金额为 Long 分，口径与两端一致）。

## 方案 B：Swift 版 FinanceOSCore（与 macOS 版同一套 Swift 逻辑）
- `apple-macos/Sources/FinanceOSCore` 是纯 Swift 领域层（Foundation 即可），已做 iOS 兼容：
  XLSX 导入等 macOS 专属能力用 `#if os(macOS)` 隔离，不参与 iOS 编译。
- 新建 Xcode 工程后可直接把 `apple-macos/Package.swift` 作为本地 Swift Package 依赖引入，
  或在 Xcode 里把 `Sources/FinanceOSCore` 加入 target。

## 建议
- UI 层面可直接参考 `apple-macos/Sources/FinanceOSMac/Views/`（SwiftUI 视图结构，
  Liquid Glass 部分仅 macOS 生效，iOS 用普通 SwiftUI 容器即可）。
- 正式 iOS 工程建议用 Xcode 创建后提交到本仓库 `ios/`（含 .xcodeproj）。
