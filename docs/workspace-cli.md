# FinanceOS 本地命令行

在仓库目录使用 JDK 21 运行。命令读取显式指定的备份和工作台配置文件，适合本地脚本、定时导出和核对流水。命令行直接使用应用共用的 Kotlin 解析和核算逻辑。备份文件由应用的数据备份功能生成，工作台配置可在「账本」页选择「导出工作台配置」生成。

```sh
./gradlew -q :shared:workspaceCli --args='--help'
./gradlew -q :shared:workspaceCli --args='validate-backup 账本备份.json'
./gradlew -q :shared:workspaceCli --args='validate-workspace 工作区.json'
./gradlew -q :shared:workspaceCli --args='export-csv 账本备份.json 流水.csv'
./gradlew -q :shared:workspaceCli --args='export-book 工作区.json 账本备份.json book-id 项目账本.json'
```

`preview-statement` 支持应用可预览的账单格式。`import-statement` 使用相同的自动列识别和 ¥0.50 最低导入金额，输出可在应用里导入的新备份。外币账单在文件末尾追加用户确认的汇率参数，含义为 1 单位原币对应的人民币金额。

```sh
./gradlew -q :shared:workspaceCli --args='preview-statement wallet.csv'
./gradlew -q :shared:workspaceCli --args='import-statement wallet.csv 导入预备.json'
./gradlew -q :shared:workspaceCli --args='preview-statement bank.ofx 7.18'
```

查账使用 ISO 8601 时间，开始包含且结束不含。以下示例采用上海时区。`audit-book` 可使用工作区的账本分配和关联信息计算特定账本的外部流水。`inbox` 和 `rules-preview` 也接受可选的账本 ID。`audit` 使用普通备份中的流水，`audit-book` 接受工作区、备份和账本 ID。输出中的 `source_references` 与 `sources` 相连，可以用 `transaction_id` 在原备份定位流水。

```sh
./gradlew -q :shared:workspaceCli --args='audit 账本备份.json 2026-11-01T00:00:00+08:00 2026-12-01T00:00:00+08:00 "餐饮支出变化"'
./gradlew -q :shared:workspaceCli --args='inbox 工作区.json 账本备份.json book-id'
./gradlew -q :shared:workspaceCli --args='rules-preview 工作区.json 账本备份.json book-id'
```

校验、预览、查账和收件箱输出 JSON 到标准输出。参数、格式和文件错误写入标准错误。导出和导入预备文件要求目标路径尚不存在。账本备份和工作区快照用于离线交换，收件箱的附件引用对应原设备本地保存的文件。

`export-book` 输出可在 FinanceOS 导入的独立账本快照。重复导出使用稳定的范围内流水编号。交换更新后的拆分时，可用新快照打开独立账本，避免已有流水的版本优先级影响结果。
