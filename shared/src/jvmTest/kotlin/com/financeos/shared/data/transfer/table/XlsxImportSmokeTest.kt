package com.financeos.shared.data.transfer.table

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * XLSX 导入冒烟：用标准 ZIP 生成器构造最小工作簿（deflate 条目覆盖纯 Kotlin inflate 路径），
 * 经 [TableTransactionImporter] 解析后断言与 CSV 路径得到相同去重 ID（Swift 端 FNV-1a-64 同源）。
 */
class XlsxImportSmokeTest {
    @Test
    fun parsesMinimalWorkbookProducedByStandardZip() {
        val bytes = buildMinimalXlsx()
        val result = TableTransactionImporter.decode(bytes)

        val transaction = result.transactions.single()
        assertEquals("bill-7211b6506f333245", transaction.id)
        assertEquals(2_350L, transaction.amount)
        assertEquals("system-other", transaction.categoryId)
    }

    private fun buildMinimalXlsx(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<si><t>交易时间</t></si><si><t>收/支</t></si><si><t>金额(元)</t></si>
<si><t>交易对方</t></si><si><t>支出</t></si><si><t>肯德基</t></si>
</sst>""".trimIndent().toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
</workbook>""".trimIndent().toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""".trimIndent().toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>
<row r="2"><c r="A2"><v>1786350600000</v></c><c r="B2" t="s"><v>4</v></c><c r="C2"><v>23.5</v></c><c r="D2" t="s"><v>5</v></c></row>
</sheetData>
</worksheet>""".trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
