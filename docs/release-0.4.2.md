# FinanceOS 0.4.2 Release Notes（Android · macOS · iOS）

发布日期：2026-09-02 · Android 0.4.2（versionCode 6）；macOS / iOS 0.4.2· 作者 isnaeguo

## 本次更新
- 主屏小组件即时刷新：在 App 内记账/导入/删除/改分类/改预算后，立即广播刷新小组件，
  不再等待系统最长 30 分钟的周期（30 分钟周期仍作为保底保留）。

## 历史（0.4.1 起，跨 Android / macOS / iOS）
- 三端同图标（青蓝紫渐变 + ¥）；0.4.1 发布；版本旁署名 isnaeguo。
- 三端流水按金额排序：金额模式忽略“天”分组、整月排序；macOS/iOS 时间模式保持按天。
- iOS 端：总览新增近 7/30 天每日消费趋势与分类消费排行；小组件（小/中/大）与局域网共享已实现
  （App Group 需付费账号启用）；XLSX/CSV/JSON 导入与去重、<0.45 元过滤。
- Android：预算支持更早月份、首页月份切换、流水按金额排序、局域网共享、2×1 小组件（金额四舍五入整数元）。
- 导入：兼容微信/支付宝账单（自动定位非首行表头、编码 GB18030/UTF-8、Tab 分隔、去重、名称取交易对方）。

## 安装
- Android：app-release.apk（正式签名，可覆盖升级 0.4.1）
- macOS：FinanceOS-0.4.2.dmg（拖拽安装；本版本不含小组件）
- iOS：Xcode Archive（工程版本 0.4.2）
