# FinanceOS（macOS Xcode 工程）— 解决小组件不被系统识别

之前的“小组件搜不到”是因为手工打包的 appex 不被 WidgetKit 完全接受。
用本 Xcode 工程构建即可得到系统认可的官方小组件扩展。

## 使用
1. 先彻底卸载旧的 FinanceOS：把 ~/Applications/FinanceOS.app 移到废纸篓，
   并执行 `pluginkit -r ~/Applications/FinanceOS.app/Contents/PlugIns/FinanceOSWidgetExtension.appex`（如仍存在）。
2. 打开本目录 `FinanceOS.xcodeproj`。
3. 选 Signing Team（App、FinanceOSWidget、FinanceOSCore 三个 target 同一 Team）。
4. 为 **FinanceOS** 与 **FinanceOSWidget** 两个 target 在 Signing & Capabilities 勾选 App Groups：
   `group.com.financeos.ios`（entitlements 已预置）。
5. Run（Scheme 选 FinanceOS / My Mac）。构建会把 widget 嵌入 App 并由系统注册。
6. 桌面右键 → 编辑小组件 → 搜索 FinanceOS 添加（小/中/大）。

## 如果编译报错
- “cannot find type X / missing module”：说明某文件缺 `import FinanceOSCore`，
  在该文件顶部补上即可（Widget 源码目录 Sources 与 MacViews 中个别文件可能需要）。
- 版本与沙盒：App 与小组件都已启用 App Sandbox + App Groups，数据通过共享容器
  `group.com.financeos.ios`（Core 的 DefaultStoreLocation 自动优先该容器）。

## 备注
- 当前工程未使用 App Sandbox / App Groups：小组件与 App 直接读写同一用户数据文件
  （~/Library/Application Support/FinanceOS/store.json），本机使用最省事。
- 若要上架/正式分发并继续支持“已启用 App Groups”的共享，再在 Xcode 里给两个 target
  勾选 App Groups `group.com.financeos.ios`（需要支持该能力的开发者账号）。
