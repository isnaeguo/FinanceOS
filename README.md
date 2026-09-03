# FinanceOS

> 跨平台个人财务管理系统：本地数据优先，Android / iOS / macOS 三端，
> 支持同一局域网内的手动数据同步、微信/支付宝账单导入与预算管理。

## Overview

FinanceOS 是一套以本地数据为核心的个人记账系统。所有数据（流水、分类、预算）
保存在设备本地，三端通过统一的 `financeos-backup` 数据格式（JSON/CSV，schema v1）
互通，并可在同一局域网内手动推送/拉取快照完成多设备数据搬运。

## Features

- 收入/支出记账（金额精确到“分”，整数存储，无浮点误差）
- 分类系统（10 个内置系统分类 + 自定义分类，跨端固定 ID）
- 月总预算与分类预算（支持历史月份）
- 每日可用预算（当天零点锁定口径）
- 流水搜索、筛选、金额/时间排序
- 月度收支汇总、分类排行、近 6 个月支出趋势、近 7/30 天按日趋势
- JSON / CSV / XLSX 导入导出；微信 / 支付宝账单宽容导入
  （GB18030/UTF-8、Tab/逗号、非首行表头、订单号或内容指纹去重）
- 本地备份创建与原子恢复（二次确认）
- 局域网手动同步（Experimental）
- 桌面小组件（本月已用 / 每日可用 / 本月剩余）

## Supported Platforms

- **Android**（minSdk 26）：Kotlin + Jetpack Compose + Room(KMP) + App Widget
- **iOS**（17+）：SwiftUI + WidgetKit（业务逻辑复用 FinanceOSCore Swift 包）
- **macOS**（26+）：SwiftUI 原生 App（SwiftPM 或 Xcode 工程两种构建方式）

## Cross-device Sync

- 同一局域网内手动同步：一端“开始接收”（HTTP 服务，默认端口 45678），
  另一端输入 `IP:端口` 后推送 / 拉取完整数据快照（ Experimental，协议为明文 HTTP，
  **无加密与认证，请仅在可信网络中使用**）。
- 合并导入按业务 ID 去重，不删除未涉及的本机数据。
- 已知限制：不支持删除传播与冲突解决；两端合并语义存在差异（见 Known Issues）；
  快照超过约 4MB（约 2.5 万条流水）时无法推送到 macOS/iOS 端。

## Architecture

- Android：`app/`（Compose UI）+ `shared/`（Kotlin Multiplatform 领域层与 Room 存储）
- iOS / macOS：`apple-macos/Sources/FinanceOSCore`（纯 Swift 领域层，iOS 经硬链接单源复用）
- 两套领域实现通过同一数据格式契约与一致的金额/时间/预算口径保持互通

## Data Model

- Transaction：id / amount_minor(整数分) / type(INCOME|EXPENSE) / category_id /
  account_id(可选) / date_time_epoch_millis(UTC 毫秒) / note
- Category：id / name / type(INCOME|EXPENSE|COMMON) / icon_key / is_system
- Budget：id / year+month / amount_limit_minor / category_id(空为月总预算)

## Import

JSON（financeos-backup schema v1）、CSV（amount_minor 无损列 + BOM）、
XLSX（zlib 解析）。宽容导入支持微信/支付宝账单列名别名与自动表头定位；
小于 0.45 元及“不计收支/退款”行自动跳过并计数。

## AI

当前版本不包含 AI 能力。

## Privacy & Security

- 数据仅保存在设备本地；无云端、无统计、无第三方网络请求
- 局域网同步为明文 HTTP 且无认证（Experimental），仅建议在可信家庭网络使用
- 签名密钥等敏感文件不入库（.gitignore 隔离）

## Project Structure

（略，见本报告 §2 Project Map）

## Tech Stack

Kotlin / Compose / Room 3 KMP / kotlinx.serialization / coroutines ·
Swift 5.9 / SwiftUI / Observation / Network.framework / WidgetKit / zlib ·
Gradle（版本目录集中管理）/ SwiftPM / XcodeGen

## Requirements

- Android：Android Studio（AGP + JDK 17）
- iOS/macOS：Xcode + Swift 6.2 工具链（macOS 目标 26+）、XcodeGen（如改 iOS 工程）
- Rust/Node 等额外依赖：无

## Build

```sh
# Android（apk 产物；本仓库未附带签名文件时仅 debug 可签）
./gradlew :app:assembleDebug

# macOS（SwiftPM 脚本打包，产出 dist/FinanceOS.app）
cd apple-macos && ./scripts/make-app.sh

# iOS
cd ios/FinanceOSiOS && xcodegen generate && open FinanceOSiOS.xcodeproj

### 一键三端 debug 构建

```sh
./scripts/build-debug-all.sh
```

脚本依次构建 Android APK、macOS App（含小组件）、iOS 模拟器 App；任一端失败立即中止并打印
是哪一端、哪条命令失败；全部成功时输出三端产物的绝对路径清单（仅 debug 构建，不依赖签名材料）。
