package com.financeos.shared.data.transfer.table

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 宽容导入器跨端一致性断言：同一行账单数据经 shared 导入生成的去重 ID 与 Swift 端
 * FNV-1a-64 逐字节一致。日期列使用绝对毫秒，避免测试受时区影响。
 */
class TableTransactionImporterTest {
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
    fun skipsRefundAndTinyRowsButCountsThem() {
        val content = lines(
            "交易时间,收/支,金额(元),交易对方,交易状态",
            "1786350600003,支出,50,商户,退款",
            "1786350600004,收入,0.2,朋友,成功",
            "1786350600005,支出,129,奶茶店,成功",
        )

        val result = TableTransactionImporter.decodeCsvText(content)

        assertEquals(1, result.transactions.size)
        assertEquals(2, result.skippedRows)
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

    private fun lines(vararg rows: String): String = rows.joinToString("\n")
}
