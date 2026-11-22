# 导入银行与钱包账单

在 macOS 或 Android 打开数据管理中的账单导入，选择文件或粘贴表格，一次识别后直接查看预览。日期和金额必填。自定义表格可以匹配表头，填写收支方向或分别选择收入和支出列。只有一个带符号的金额列时，默认正数为收入、负数为支出。信用卡等正数为支出的账单可反转此规则，或直接选择明确的收支方向列。

```csv
日期,金额,收支,分类,备注,账户,交易单号
2026-11-15,18.50,支出,餐饮,午餐,银行卡,20261115-01
2026-11-16,120.00,收入,其他,差旅报销,银行卡,20261116-01
```

支持逗号、Tab 或分号分隔的文本、XLSX 首个工作表，以及 OFX、QFX、QBO 的银行和信用卡流水。表格可使用 UTF‑8、UTF‑16 BOM 或 GB18030。推荐使用 YYYY-MM-DD 日期，含时区的时间可用 ISO 8601。每次文件上限为 16 MiB，表格上限为 50,000 行。请先解压钱包提供的 ZIP 文件。

外部账单默认跳过人民币入账金额低于 ¥0.50 的流水，正好 ¥0.50 保留。外币在换算并舍入为人民币后执行此规则。FinanceOS 自身导出的 CSV、JSON 备份和手动记账保留原金额。

预览会标出无效行和表内重复交易编号。检查金额、收支方向及异常行后再确认入账。相同的账单重复导入时，本地已有和已删除的记录会跳过。账单有交易编号时优先使用编号。无编号且每项相同的多笔消费会保留，重叠区间建议使用同一种原始账单格式和同一种账户映射。跨来源的同一笔消费可能需要手工核对。

FinanceOS 账本以人民币记账。可识别的单一外币账单须先填写汇率，应用按每笔流水换算后确认入账。一个文件有多种币种时请按币种拆分。对于缺少币种字段的文件，请先检查并将金额换算为人民币。Apple Card 信用卡还款标记为内部资金调拨，账单导入会跳过，避免把还款计为收入。

## 支付来源

| 来源 | 可用方式 |
| --- | --- |
| 银行卡、信用卡 | 从网银或发卡行导出 CSV、XLSX、OFX、QFX 或 QBO。PDF 账单可先整理为上方模板。 |
| Apple Card | 在 Wallet 中打开 Apple Card，选择月结单并导出 CSV、OFX、QFX 或 QBO，也可在 Apple Card 网站按日期导出。美国 Apple Card 流水中的美元需在入账前换算。 [Apple Card 导出说明](https://support.apple.com/en-us/102284) |
| Apple Pay | 普通银行卡的 Apple Pay 消费优先从发卡行对账单导入。Apple 钱包可查看部分近期消费。FinanceKit 交易数据需要符合条件的美国或英国 iPhone 应用、用户授权和 Apple 发放的权限。 [Apple Pay 交易记录](https://support.apple.com/zh-cn/104954) · [FinanceKit 可用范围](https://developer.apple.com/financekit/) |
| Google Pay / Google Wallet | 在 Google Takeout 中选取相应产品导出，解压后检查是否有包含日期、金额的交易表格。CSV/TSV 可直接预览或映射列，只有 JSON/HTML 时请先整理为表格，或者使用发卡行账单。Google Pay 的公开接口用于收款，Google Wallet 的公开接口用于发行和管理凭证。 [Google 导出说明](https://support.google.com/googlepay/answer/9015738?hl=en-IN) · [Google 开发者文档](https://developers.google.com/pay/api/web/support/faq) |
| Xiaomi Pay / 小米钱包 | 中国大陆的 Xiaomi Pay 可使用绑定银行卡的发卡行对账单。小米隐私中心还可申请个人数据副本，具体产品和数据内容以实际导出为准。 [小米支付说明](https://www.mi.com/service/consult/mipay-Smart%20Card) · [小米隐私中心](https://privacy.mi.com/support/faq?locale=zh-cn) |
| 微信支付 | 在「我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单」下载用于个人对账的文件，解压后导入。 [财付通说明](https://posts.tenpay.com/posts/0a4ec1f9626a576b00b7c67f6d3ad7a9.html) |
| 支付宝 | 登录支付宝下载余额收支明细，选择实际需要的交易账户或账单范围。 [支付宝说明](https://cschannel.alipay.com/mobile/helpDetail.htm?help_id=201603346502) |

快捷指令的 [Wallet 交易触发器](https://support.apple.com/guide/shortcuts/apd65c67538a/ios) 可在 iPhone 上用于新增刷卡后触发自动化。它提供实时触发，历史流水仍从发卡行或 Apple Card 导出。
