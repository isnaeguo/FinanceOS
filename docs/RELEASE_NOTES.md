# FinanceOS Release Notes —— 0.4.2（Android / macOS / iOS）

发布日期：2026-09-02 · 作者：isnaeguo
版本：Android 0.4.2（versionCode 6，正式签名）· macOS 0.4.2 · iOS 0.4.2（Xcode 工程）
发布形态：Android app-release.apk / macOS FinanceOS-0.4.2.dmg / iOS Xcode Archive

> 三端共享同一套业务口径（金额一律“分”、预算/每日可用/排序/导入去重规则一致），
> 数据与备份格式互通（financeos-backup schema v1，JSON/CSV/XLSX 兼容）。

---

## 0.4.2（本次）
- **Android 小组件即时刷新**：App 内记账/导入/删除/改分类/改预算后立即广播刷新小组件，无需等待系统 30 分钟周期（周期保留为保底）。
- macOS / iOS 版本号与“关于”文案同步至 0.4.2。
- 说明：macOS 分发版不含小组件（已回退）；iOS 小组件已实现，App Group 数据共享需付费开发者账号开启。

## 0.4.1
- **三端图标统一**：青→蓝→紫渐变 + 白色 ¥（Android 自适应图标 / iOS 满幅图标集 / macOS icns）。
- 版本号旁统一署名 isnaeguo。
- **XLSX 解析跨平台化**：zip 解压改用 zlib（iOS/macOS 通用，不再依赖 macOS ditto）。
- **流水金额排序**：按金额排序时忽略“天”分组、整月全局排序（三端）；默认时间视图仍按天分组（macOS/iOS）。
- **iOS 完善**：总览新增近 7/30 天每日消费趋势与分类消费排行；iOS 小组件（小/中/大）与局域网共享（同协议 45678）；Xcode 原生工程落地。
- **Android 小组件 2×1**：单行三栏（本月已用/每日可用/本月剩余），金额四舍五入为整数元。
- iOS App 图标修复（满幅不透明，消除黑边）。

## 0.4.0
- **局域网手动共享（macOS + Android）**：明文 HTTP 协议（GET/POST /api/snapshot、/api/ping），
  一端开启接收、另一端填 `http://IP:45678` 拉取/推送，按 ID 合并导入，与微信/支付宝账单导入同一套去重与过滤。
- **小组件首版**：
  - macOS：WidgetKit 小/中/大（本月已用/每日可用(重点)/本月剩余(重点)）；
  - Android：2×2 或 4×2 可拉伸小组件，随后演进为 2×1；
- **首页月份切换（Android）**：浏览当前月与历史月，预算进度/排行随月联动，今日可用仅本月展示。
- **预算支持更早月份（macOS + Android）**：过去任意月可查看与设置预算，未来仍限制到“下月”。
- **账单导入体系（Android，逐步与 mac 对齐）**：
  - 文件选择器放开为任意文件并按内容识别（修复“无法选择文件”）；
  - 统一导入入口：JSON / CSV / XLSX 自动识别；
  - 微信/支付宝账单适配：自动跳过顶部说明行定位表头、中文/英文列名别名、UTF-8/GB18030、逗号/Tab 分隔、
    “收/支”方向、支付方式入账户、名称取交易对方、不计收支/退款/0 元/<0.45 元自动跳过；
  - 重复导入去重：业务单号或内容指纹作稳定 ID，同一文件/跨端再导入不会重复记账。
- **iOS 准备（KMP 侧）**：shared 启用 iosArm64 / iosSimulatorArm64 并导出 FinanceOSShared.framework；
  macOS Swift 工程（apple-macos）并入仓库统一 git 管理。
- 首次 release 化：正式签名密钥、Release 打包脚本、DMG 拖拽安装分发流程。

---

## 安装与升级
- Android：直接安装 `app-release.apk`（正式签名），可覆盖升级 0.4.1。
- macOS：`FinanceOS-0.4.2.dmg` 拖入 Applications；首次打开如提示未知开发者，右键→“打开”。
- iOS：Xcode 打开 `ios/FinanceOSiOS/FinanceOSiOS.xcodeproj`，选择 Team 后 Run / Archive。

---

## 0.5.0（局域网同步配对加密）
- 局域网同步启用**配对码认证 + 端到端加密**（shared 单源实现）：
  - 接收端开启共享时展示一次性 10 位配对码（Base32、剔除 0/O/1/I），停止接收即失效；
  - AES-256-CBC + HMAC-SHA256（Encrypt-then-MAC），PBKDF2-HMAC-SHA256（150k 次）派生密钥；
  - 请求/响应体加密，明文快照不再出现于局域网；
  - 错误码：401 配对码错误、426 旧版客户端升级提示、429 连续失败限速、400 过期/重放；
  - 本版本与旧版（明文 0.4.x）**不互通**：旧客户端访问会收到 426 升级提示。
- 详见 `docs/lan-sync-protocol.md`。

---

## 1.0.0（正式版）

首个正式版本，包含 0.4.x 以来的全部能力：

- **三端统一业务内核（KMP）**：模型/存储（Room）/合并/备份编解码/账单导入全部由 shared 单源提供，
  Android、iOS、macOS 共用同一实现；schema v2（软删墓碑 + 修改时间裁决 + 三端确定性收敛合并）。
- **局域网同步配对加密**：一次性 10 位配对码 + PBKDF2/AES-CBC+HMAC 端到端加密，
  旧明文客户端收到 426 升级提示（与 0.4.x 不互通）。
- **账单导入分类修复**：识别「交易分类」等账单列名，平台类目（餐饮美食/交通出行/日用百货…）
  关键词映射到系统分类；重新导入同一账单不产生重复且可补正历史分类。
- iOS/macOS 旧 store.json 首次启动自动迁移至 Room（原文件改名保留）。

### 安装与升级
- Android：安装 `app-release.apk`（正式签名），可从 0.4.x 覆盖升级（数据保留）。
- macOS：`FinanceOS-1.0.0.dmg` 拖入 Applications；首次打开如提示未知开发者，右键→"打开"。
- iOS：Xcode 打开 `ios/FinanceOSiOS/FinanceOSiOS.xcodeproj`，选择 Team 后 Run / Archive。
- 旧版局域网明文同步双方需同时升级到 1.0.0 才能互相同步。

---

## 1.0.1（修复与视觉）

- **月总预算口径修正（三端）**：预算“已使用”按净支出（支出 − 收入）统计——当月有收入进账会抵扣
  预算消耗，结余即未用完；展示在净支出≤0 时显示“本月有结余”。分类预算口径不变。
- **Android 品牌化视觉**：跨端设计 token 单源、Material 3 主题 token 化、品牌光斑背景 +
  玻璃卡片 + TopAppBar/hero 模糊（API<31 自动回退半透明）+ 品牌渐变主按钮。
- 安装/升级方式同 1.0.0（Android `app-release.apk` 可直接覆盖 1.0.0；macOS 替换
  `FinanceOS-1.0.1.dmg`；iOS 重新 Run/Archive）。

---

## 1.0.2（口径统一）

- **跨端口径统一**：首页「近 6 个月支出」、每日支出趋势与月总预算全部按**净支出（支出 − 收入）**统计，
  收入≥支出时显示负值（有结余）；Android、iOS、macOS 由 shared 单源驱动，结果三端一致。
- 设置页版本号改为动态读取（不再硬编码），随构建版本实时显示。
- Android 玻璃质感与暗色文字对比细修。

---

## 1.0.3（Android 曲线）

- Android「近 6 个月支出」趋势连线由折线改为平滑曲线（三次贝塞尔过数据点）；
  同时带上此前口径修正：趋势按净支出（支出 − 收入）统计，三端一致。
