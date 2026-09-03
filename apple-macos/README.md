# FinanceOS for macOS

基于 [/Users/insaneguo/FinanceOS](../FinanceOS) 的 `shared` 领域模型与业务规则构建的原生 macOS 记账应用，
SwiftUI + Liquid Glass（macOS 26 Tahoe）外观。金额一律使用**最小货币单位（分）**，计算不经过浮点。

## 功能（对齐 FinanceOS v0.1/v0.2 范围）

- 记录收入 / 支出，内置分类 + 自定义分类，支持备注与账户
- 按月份浏览历史流水，支持按类型 / 分类 / 账户筛选与备注关键词搜索，按天分组展示
- Dashboard：月度收支汇总、月总预算进度、**每日可用预算**、近 6 个月支出趋势、
  近 7 / 30 天消费趋势、分类消费排行、最近流水
- 预算：月总预算与分类预算（仅管理本月与下月，与 Android 端一致的约束）
- 数据：JSON 完整备份 / 合并导入 / 完整恢复、CSV 流水无损导入导出（含 BOM 与 RFC4180 转义）
- 局域网共享：同一局域网内手动「推 / 拉快照」，明文 HTTP/1.1（NWListener + URLSession），两端与 Android 一致，合并导入不删本机数据
- 小组件：FinanceOS 本月概览（小 / 中 / 大），读取同一数据文件并复用核心计算，数据落盘后即时刷新
- 与 Android 端 FinanceOS 数据文件完全互通（`financeos-backup` schema v1）

## 目录

```
Sources/FinanceOSCore/   可复用领域层（模型、金额、计算、JSON/CSV 编解码、FinanceStore 本地存储）
Sources/FinanceOSMac/    SwiftUI 界面（Liquid Glass）
Sources/FinanceOSWidgetWidgets/   WidgetKit 小组件源码（由脚本与 Domain 源码一起编译）
Tests/FinanceOSChecks/   领域移植校验（与 Android 端用例对齐的断言）
scripts/                 打包与图标生成脚本
dist/FinanceOS.app       Release 产物
dist-debug/FinanceOS.app Debug 产物（含小组件扩展）
```

## 构建与运行

环境：macOS 26 + Swift 6.2（本机使用 Command Line Tools，无 Xcode 亦可）。

```sh
# 快速启动（调试）
swift run FinanceOSMac

# 领域校验（钱/预算/汇总/趋势/编解码/存储）
swift run FinanceOSChecks

# Release 打包成 .app（含图标与 ad-hoc 签名）
./scripts/make-app.sh
open dist/FinanceOS.app

# Debug 打包（含 WidgetKit 小组件扩展，产物在 dist-debug/FinanceOS.app）
./scripts/make-app-debug.sh

# 单独编译小组件扩展（.build/widget/FinanceOSWidgetExtension.appex）
./scripts/build-widget.sh
```

「局域网共享」：在侧边栏进入后，一方点击「开始接收」，另一方输入其「本机地址」即可推拉快照。
小组件需先通过 `make-app-debug.sh` 生成并安装（`~/Applications/FinanceOS.app`），支持小 / 中 / 大三种尺寸。

数据保存在 `~/Library/Application Support/FinanceOS/store.json`
（应用内「数据与备份」页可查看并一键打开所在文件夹）。

## 说明

- 移植自 FinanceOS `shared` 的类与函数逐一对齐（见 Tests/FinanceOSChecks/Runner.swift）。
- 本机无完整 Xcode，`.app` 采用 Command Line Tools 的 `swift build` + 手工组装与 ad-hoc 签名生成；
  如需正式分发请用 Xcode 归档并替换签名与图标资源。

## 工程结构变更（schema v2 内核升级）
- 业务内核迁至 KMP `shared`（macosArm64 静态框架 FinanceOSShared）；macOS 主工程为 `../apple-xcode`
  （xcodebuild）。本 SwiftPM 工程只保留 `FinanceOSChecks` 冒烟复验：
  `./scripts/make-xcframework.sh && swift run`。
- 旧 Swift 领域层 `FinanceOSCore` 已移除；视图层保留在 `Sources/FinanceOSMac/Views`（由 apple-xcode 经
  symlink 编译），仅数据来源替换为 FinanceStoreAdapter（Room）。
- 小组件与 App 直接读取 App Group 内 Room 库文件（`group.com.financeos.ios`），不再依赖 store.json。
- 打包：`scripts/make-app.sh`（Release）/ `make-app-debug.sh`（Debug）现调用 xcodebuild。
