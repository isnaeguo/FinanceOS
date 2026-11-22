package com.financeos.shared.data.transfer.table

import com.financeos.shared.data.transfer.DataTransferException
import com.financeos.shared.data.transfer.TransactionCsvCodec
import com.financeos.shared.domain.model.Transaction
import com.financeos.shared.domain.model.TransactionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 宽容导入器跨端一致性断言：同一行账单数据经 shared 导入生成的去重 ID 与 Swift 端
 * FNV-1a-64 逐字节一致。日期列使用绝对毫秒，避免测试受时区影响。
 */
class TableTransactionImporterTest {
    @Test
    fun detectsCurrencyInMappedCreditAndDebitHeaders() {
        val prepared = TableTransactionImporter.prepareText(
            "Date,Credit (USD),Debit (USD)\n2026-11-01,12.34,\n2026-11-02,,2.50",
        )
        val result = prepared.preview(
            categories = emptyList(),
            importedAtEpochMillis = 0,
            mapping = StatementColumnMapping(date = "Date", credit = "Credit (USD)", debit = "Debit (USD)"),
        )
        assertEquals("USD", result.sourceCurrencyCode)
        assertTrue(result.requiresCurrencyConversion)
        assertEquals(result, prepared.preview(categories = emptyList(), importedAtEpochMillis = 0))
        assertEquals(listOf(1234L, 250L), result.transactions.map { it.amount })
    }

    @Test
    fun generatesSameIdAsSwiftForWechatStyleRow() {
        val content = lines(
            "交易时间,收/支,金额(元),交易对方,支付方式,商品",
            "1786350600000,支出,23.5,肯德基,微信支付,午饭套餐",
        )

        val result = TableTransactionImporter.decodeCsvText(content)

        val transaction = result.transactions.single()
        // 与 Swift 端对同一行（note=交易对方=肯德基）计算的 FNV-1a-64 一致。
        assertEquals("bill-7211b6506f333245", transaction.id)
        assertEquals(2_350L, transaction.amount)
        assertEquals("system-other", transaction.categoryId)
        assertEquals("肯德基", transaction.note)
    }

    @Test
    fun handlesCounterpartyWithCommaAndQuote() {
        val content = lines(
            "交易时间,收/支,金额(元),交易对方",
            "1786350600001,支出,128,\"美团, 优选\"\"小铺\"\"\"",
        )

        val transaction = TableTransactionImporter.decodeCsvText(content).transactions.single()
        assertEquals("bill-62dbb7eda8b32413", transaction.id)
    }

    @Test
    fun usesOrderIdWhenPresent() {
        val content = lines(
            "交易时间,收/支,金额(元),交易对方,交易单号",
            "1786350600002,收入,1000,公司,WX 9999",
        )

        val transaction = TableTransactionImporter.decodeCsvText(content).transactions.single()
        assertEquals("bill-WX9999", transaction.id)
        assertEquals(100_000L, transaction.amount)
    }

    @Test
    fun skipsSmallExternalIncomeAndExpenseButKeepsFiftyCents() {
        val content = lines(
            "交易时间,收/支,金额(元),交易对方,交易状态",
            "1786350600003,支出,50,商户,退款",
            "1786350600004,收入,0.49,朋友,成功",
            "1786350600005,支出,-0.49,奶茶店,成功",
            "1786350600006,收入,0.50,朋友,成功",
            "1786350600007,支出,0.50,奶茶店,成功",
        )

        val result = TableTransactionImporter.decodeCsvText(content)

        assertEquals(listOf(50L, 50L), result.transactions.map { it.amount })
        assertEquals(listOf(TransactionType.INCOME, TransactionType.EXPENSE), result.transactions.map { it.type })
        assertEquals(3, result.skippedRows)
        assertTrue(result.issueMessages.single().contains("已跳过 2 笔入账金额低于 ¥0.50"))
    }

    @Test
    fun smallExternalAmountsReturnAnEmptyPreviewWithExplanation() {
        val result = TableTransactionImporter.decodeCsvText(
            lines("日期,金额,交易编号", "2026-08-10,-0.01,external-reference"),
        )

        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.skippedRows)
        assertEquals(0, result.invalidRows)
        assertTrue(result.issueMessages.single().contains("¥0.50"))
    }

    @Test
    fun nativeFinanceOsCsvRoundTripKeepsSmallLedgerAmounts() {
        val transaction = Transaction(
            id = "small-existing-transaction",
            amount = 1L,
            type = TransactionType.EXPENSE,
            categoryId = "system-other",
            dateTime = Instant.parse("2026-08-10T01:02:03Z"),
        )
        val codec = TransactionCsvCodec()
        val encoded = codec.encode(listOf(transaction))

        assertEquals(1L, codec.decode(encoded).single().amount)
        val preview = TableTransactionImporter.decodeCsvText(encoded)
        assertEquals(listOf(transaction), preview.transactions)
        assertEquals(0, preview.skippedRows)
    }

    @Test
    fun filtersForeignCurrencyOnlyAfterRoundingTheConvertedLedgerAmount() {
        val result = TableTransactionImporter.decodeCsvText(
            lines(
                "Date,Amount (USD)",
                "2026-08-10,0.06",
                "2026-08-11,-0.07",
                "2026-08-12,0.10",
            ),
        )

        assertTrue(result.requiresCurrencyConversion)
        assertEquals(listOf(6L, 7L, 10L), result.transactions.map { it.amount })
        assertEquals(0, result.skippedRows)

        val converted = TableTransactionImporter.convertToCny(result, "7.2")
        assertFalse(converted.requiresCurrencyConversion)
        assertEquals(listOf(50L, 72L), converted.transactions.map { it.amount })
        assertEquals(result.transactions.drop(1).map { it.id }, converted.transactions.map { it.id })
        assertEquals(1, converted.skippedRows)
        assertTrue(converted.issueMessages.first().contains("¥0.50"))
    }

    @Test
    fun amountsRoundingToZeroAfterConversionAreSkipped() {
        val result = TableTransactionImporter.decodeCsvText(
            lines("Date,Amount (JPY)", "2026-08-10,0.01"),
        )

        val converted = TableTransactionImporter.convertToCny(result, "0.004")
        assertFalse(converted.requiresCurrencyConversion)
        assertTrue(converted.transactions.isEmpty())
        assertEquals(1, converted.skippedRows)
        assertTrue(converted.issueMessages.first().contains("¥0.50"))
    }

    @Test
    fun importsExistingIdsVerbatim() {
        val content = lines(
            "编号,交易时间,收/支,金额(元),交易对方",
            "imported-tx,1786350600006,支出,666,商户",
        )

        val transaction = TableTransactionImporter.decodeCsvText(content).transactions.single()
        assertEquals("imported-tx", transaction.id)
    }

    @Test
    fun mapsPlatformCategoryNamesToSystemCategories() {
        // 支付宝类目名与系统分类名不同：关键词映射决定显示分类；ID 不受影响。
        val content = lines(
            "交易时间,收/支,金额(元),交易分类,交易对方",
            "1786350600000,支出,23.5,餐饮美食,肯德基",
            "1786350600001,支出,2,交通出行,公交公司",
            "1786350600002,支出,45,日用百货,便利店",
            "1786350600003,支出,100,医疗健康,药店",
        )

        val byId = TableTransactionImporter.decodeCsvText(content).transactions.associateBy { it.amount }

        assertEquals("system-food", byId[2_350L]?.categoryId)
        assertEquals("system-transport", byId[200L]?.categoryId)
        // “日用百货”同时含“日用/百货”，日用品优先于购物。
        assertEquals("system-daily-needs", byId[4_500L]?.categoryId)
        assertEquals("system-other", byId[10_000L]?.categoryId)
    }

    @Test
    fun importsBankTsvWithPreambleCreditsDebitsAndMultilineNotes() {
        val content = lines(
            "账单说明, 非表头",
            "日期\t收入金额\t支出金额\t备注",
            "2026-08-10\t0\t1.20\t\"早餐\n发票\"",
            "2026-08-11\t20.00\t0\t报销",
            "共计两笔",
        )

        val preview = TableTransactionImporter.decodeCsvText(content)

        assertEquals("TSV", preview.format)
        assertEquals(0, preview.invalidRows)
        assertEquals(listOf(TransactionType.EXPENSE, TransactionType.INCOME), preview.transactions.map { it.type })
        assertEquals(listOf(120L, 2_000L), preview.transactions.map { it.amount })
        assertEquals("早餐\n发票", preview.transactions.first().note)
    }

    @Test
    fun supportsManualColumnMappingAndPolarity() {
        val content = lines(
            "When;How Much;Who",
            "2026-08-10;2.25;Cafe",
            "2026-08-11;3.00;Books",
        )
        val columns = TableTransactionImporter.inspectCsvText(content)
        assertEquals(listOf("When", "How Much", "Who"), columns.headers)
        val preview = TableTransactionImporter.decodeMappedCsvText(content, emptyList(), 0, StatementColumnMapping(
            date = "When",
            amount = "How Much",
            merchant = "Who",
            invertAmountSign = true,
        ))
        assertEquals(listOf(225L, 300L), preview.transactions.map { it.amount })
        assertTrue(preview.transactions.all { it.type == TransactionType.EXPENSE })
    }

    @Test
    fun preparedFileCanRemapWithoutReadingItsOriginalBytes() {
        val bytes = lines(
            "日期,金额,另一个金额,交易单号,备注",
            "2026-08-10,-2.25,-3.50,external-id,午餐",
        ).encodeToByteArray()
        val prepared = TableTransactionImporter.prepare(bytes)
        bytes.fill(0)

        val originalMapping = prepared.columns.suggestedMapping
        val automatic = prepared.preview(emptyList(), 0).transactions.single()
        val corrected = prepared.preview(
            emptyList(), 0, originalMapping.copy(amount = "另一个金额"),
        ).transactions.single()

        assertEquals(225L, automatic.amount)
        assertEquals(350L, corrected.amount)
        assertEquals(automatic.id, corrected.id)
        assertEquals("午餐", corrected.note)
    }

    @Test
    fun flagsBadRowsAndDeduplicatesExplicitReferences() {
        val content = lines(
            "date,amount,reference,memo",
            "2026-02-29,19.00,a,bad calendar date",
            "2026-08-10,0.0a,b,bad amount",
            "2026-08-10,-23.20,c,valid",
            "2026-08-10,-23.20,c,valid",
            "2026-08-10,-92233720368547758.09,d,overflow",
        )
        val result = TableTransactionImporter.decodeCsvText(content)
        assertEquals(3, result.invalidRows)
        assertEquals(1, result.duplicateRows)
        assertEquals(2_320L, result.transactions.single().amount)
        assertTrue(result.issueMessages[0].startsWith("第 2 行"))
    }

    @Test
    fun importsAppleCardPurchasesAndRefundsAndConvertsToCny() {
        val content = lines(
            "Transaction Date,Clearing Date,Description,Merchant,Category,Type,Amount (USD)",
            "01/03/2026,01/04/2026,Latte,Local Cafe,Food & Drink,Purchase,4.23",
            "01/09/2026,01/09/2026,Returned part,Local Cafe,Food & Drink,Refund,-1.45",
            "01/15/2026,01/15/2026,Card repayment,Bank,Payment,Payment,-24.00",
        )

        val unconverted = TableTransactionImporter.decodeCsvText(content)
        assertEquals("USD", unconverted.sourceCurrencyCode)
        assertTrue(unconverted.requiresCurrencyConversion)
        assertEquals(1, unconverted.skippedRows)
        assertEquals(listOf(TransactionType.EXPENSE, TransactionType.INCOME), unconverted.transactions.map { it.type })

        val converted = TableTransactionImporter.convertToCny(unconverted, "7.15")
        assertFalse(converted.requiresCurrencyConversion)
        assertEquals("USD", converted.sourceCurrencyCode)
        assertEquals(unconverted.transactions.map { it.id }, converted.transactions.map { it.id })
        assertEquals(listOf(3_024L, 1_037L), converted.transactions.map { it.amount })
        assertFailsWith<DataTransferException> { TableTransactionImporter.convertToCny(unconverted, "0") }
    }

    @Test
    fun parsesOfxSgmlWithAccountScopedFitIdAndExplicitTimezone() {
        val text = """
            OFXHEADER:100
            <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS>
            <CURDEF>CNY
            <BANKACCTFROM><BANKID>1<ACCTID>2</BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260810083000.000[8:CST]<TRNAMT>-6.70<FITID>ab<NAME>A &amp; B<MEMO>Lunch</STMTTRN>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260810083000.000[8:CST]<TRNAMT>-6.70<FITID>ab<NAME>A &amp; B<MEMO>Lunch</STMTTRN>
            </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
        """.trimIndent()
        val result = TableTransactionImporter.decode(text.encodeToByteArray())
        val transaction = result.transactions.single()

        assertEquals("OFX", result.format)
        assertEquals(1, result.duplicateRows)
        assertEquals(670L, transaction.amount)
        assertEquals("A & B · Lunch", transaction.note)
        assertEquals(Instant.parse("2026-08-10T00:30:00Z"), transaction.dateTime)
    }

    @Test
    fun preservesEqualPurchasesWithoutExternalIdsAcrossReimports() {
        val content = lines(
            "date,amount,merchant",
            "2026-08-10,-1.50,Cafe",
            "2026-08-10,-1.50,Cafe",
        )
        val first = TableTransactionImporter.decodeCsvText(content)
        val second = TableTransactionImporter.decodeCsvText(content)

        assertEquals(2, first.transactions.map { it.id }.toSet().size)
        assertEquals(first.transactions, second.transactions)
    }

    @Test
    fun rejectsMixedCurrenciesAndMalformedQuoting() {
        assertFailsWith<DataTransferException> {
            TableTransactionImporter.decodeCsvText(lines("date,amount,currency", "2026-08-10,12.00,USD", "2026-08-11,11.02,EUR"))
        }
        assertFailsWith<DataTransferException> {
            TableTransactionImporter.decodeCsvText(lines("date,amount,note", "2026-08-10,12.00,\"unfinished note"))
        }
    }

    private fun lines(vararg rows: String): String = rows.joinToString("\n")
}
