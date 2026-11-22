# FinanceOS

FinanceOS 是一套本地优先的个人账本。Android、macOS 和 iOS 共用 Kotlin Multiplatform 业务内核、Room 数据库和备份格式。

## 功能

Android 和 macOS 跟随系统浅色或深色外观。财务工作台使用高对比金额、细分隔线和原生绘制的几何图标。页面短转场和几何加载动画尊重系统减少动态效果设置。图表使用原生绘制，账本筛选、对账、预测和查账计算在后台完成。

- 记录收入与支出，可添加分类、账户、日期和备注。流水可按月浏览、搜索、筛选和排序。
- 查看月度收支、分类支出排行、近 6 个月趋势和近 7/30 天趋势。管理月总预算与分类预算，查看按当天零点口径计算的每日可用预算。
- Android 和 macOS 支持文件或粘贴导入账单。选择列对应关系，预览收支方向、金额、重复和异常流水后再确认。支持 CSV、TSV、XLSX、OFX、QFX 和 QBO。
- Android 和 macOS 支持 AI 分类建议与月度消费建议。分类建议逐笔确认，流水在生成建议后发生变更时需重新核对。
- FinanceOS JSON 完整备份可合并或替换恢复。兼容读取 schema v1 和 v2，导出使用 v2。无损流水 CSV 可用于电子表格核对。
- 同一局域网内使用一次性会话配对码手动推送或拉取快照。快照使用应用层加密与完整性校验，合并按更新版本处理更改和软删除。详见 [局域网同步协议](docs/lan-sync-protocol.md)。
- 各端小组件可查看本月支出、预算剩余和每日可用预算。

## 财务工作台

- **账户与对账**可录入期初余额和对账单余额，匹配转账、信用卡还款、退款和重复流水。已经关联的转账在账户余额中保留两边，在工作台收支中排除两边。转账两边的人民币入账金额必须相同，手续费单独记账。退款默认归入原消费账本，原交易拆分时按原比例分摊。导入的净化规则会排除人民币入账金额低于 ¥0.50 的流水，预览可查看跳过笔数，对账时按需手动补录。
- **收件箱**保存凭证、退款、报销、保修和续费待办，并提示待分类、疑似重复和周期支出涨价。macOS 支持拖入账单或票据，使用系统识别图片文字和提取 PDF 文本。Android 可拍照、选择图片或从系统分享接收凭证，使用随应用打包的中英文字模型识别票据。
- **现金流**可查看未来 30 或 90 天的余额和最低点，设置单次、每周、每月计划，按账本或情景试算。全账本从已登记的人民币期初余额和人民币入账净额开始，单账本从该账本累计净额开始。
- **AI 助手**的账本对话可追问“上月呢”“那交通呢”或“哪笔最大”，并跳转到流水证据。默认在本机计算，可选的 AI 概括仅发送数值汇总和匿名事实编号。通用对话使用相同的 Responses 兼容接口，可讨论记账和预算，仅发送用户在通用对话中输入的消息及该对话的近期回复。聊天记录保存在当前应用进程中，可清空。分类规则先预览，再确认应用。
- **账本与资产**可归档和分摊家庭、旅行与工作流水，按币种查看余额并查看 12 周人民币净资产。所选账本可离线导出为 FinanceOS 备份，供他人导入。导出包含该范围内的流水和关联分类，去除原账户标识和备注。多人分别维护自己的账本。

现有 JSON 备份与局域网快照继续交换流水、分类和预算。账户、账本分配、计划、规则和待办保存在本机工作台配置中，可单独导出 JSON 供 [本地命令行](docs/workspace-cli.md) 校验和查询。凭证原件保存在各设备的应用数据中。

## 导入支付账单

- **Apple Card** 可从 Wallet 导出 CSV、OFX、QFX 或 QBO。应用识别美元账单，按用户输入的结算汇率预览换算。
- **Apple Pay 和 Xiaomi Pay** 的银行卡交易优先使用发卡行的对账单。
- **Google Pay / Google Wallet** 可尝试从 Google Takeout 导出。解压后如有交易表格，即可选取文件或粘贴账单，JSON 和 HTML 可整理为表格。
- **微信支付和支付宝** 支持导出的常见表格，可在自动识别后调整列。

导出路径、模板、币种和去重规则见 [银行与钱包账单导入](docs/statement-import.md)。账本统一以人民币记账，表格含有多种已知币种时需先按币种拆分。

## AI 配置和隐私

在 macOS 侧边栏或 Android 设置页打开「AI 账单助手」，填写服务商的 HTTPS API 基地址、模型 ID 和服务要求的密钥。输入基地址时默认调用 OpenAI 兼容的 Responses API，也可以直接输入完整的 `/responses` 地址。服务商和模型 ID 可自由填写。旧配置与显式填写的 `/chat/completions` 地址使用 Chat Completions。密钥保存在系统钥匙串或由 Android Keystore 保护，配置可在应用内清除。

只有发起分析时才会向所选服务发送数据。分类在所选月份内每次最多处理 200 笔含备注的「其他」流水，每批 20 笔，发送收支方向、可用分类及经基础脱敏的备注。逐笔确认时可选现有分类或新建分类并应用。月度分析发送选定月份的汇总金额、分类名与预算。请在使用前确认所选服务商和备注内容。分类与消费建议均由用户核对后使用。 [Responses API 接口参考](https://developers.openai.com/api/reference/resources/responses/methods/create)

## 架构

| 目录 | 用途 |
| --- | --- |
| `shared/` | 领域模型、金额和预算计算、Room 存储、备份、账单解析、AI 请求与响应 |
| `app/` | Android Jetpack Compose 应用和小组件 |
| `apple-adapter/` | SwiftUI 与共享内核之间的数据适配 |
| `apple-xcode/`、`apple-macos/` | macOS Xcode 工程、SwiftUI 应用和小组件 |
| `ios/` | iOS SwiftUI 应用和小组件 |

## 构建

Android 最低 API 26，构建使用 JDK 21 和 SDK Platform 37。macOS 主工程需要完整 Xcode、macOS 26 SDK 及 Apple Silicon。iOS 最低版本为 17。

```sh
# 在仓库根目录运行共享内核测试和 Android 构建
./gradlew :shared:jvmTest :app:testDebugUnitTest :app:assembleDebug

# macOS 调试 App
./apple-macos/scripts/make-app-debug.sh

# Android、macOS 和 iOS 模拟器一键调试构建
./scripts/build-debug-all.sh
```

Android 调试 APK 输出在 `app/build/outputs/apk/debug/`，macOS 调试 App 输出在 `apple-macos/dist-debug/`。macOS 的 App Group 和小组件分发需要在 Xcode 选择具有对应能力的签名团队。
