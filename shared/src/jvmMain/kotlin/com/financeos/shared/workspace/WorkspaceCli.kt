package com.financeos.shared.workspace

import com.financeos.shared.ai.FinanceAudit
import com.financeos.shared.ai.FinanceAuditAnswer
import com.financeos.shared.data.transfer.FinanceDataJsonCodec
import com.financeos.shared.data.transfer.TransactionCsvCodec
import com.financeos.shared.data.transfer.table.TableTransactionImporter
import com.financeos.shared.domain.model.DefaultCategories
import com.financeos.shared.domain.model.FinanceDataSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlin.system.exitProcess
import kotlin.time.Instant

fun main(args: Array<String>) {
    if (args.isEmpty() || args.contentEquals(arrayOf("--help"))) {
        println(usage)
        return
    }
    try {
        WorkspaceCli().run(args.toList())?.let(::println)
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        exitProcess(2)
    } catch (error: IOException) {
        System.err.println(error.message)
        exitProcess(2)
    }
}

private class WorkspaceCli {
    private val ledgerCodec = FinanceDataJsonCodec()
    private val analytics = FinanceWorkspaceAnalytics()
    private val automation = WorkspaceAutomation()

    fun run(args: List<String>): String? = when (args.first()) {
        "validate-backup" -> {
            expect(args, 1)
            val ledger = loadLedger(args[1])
            buildJsonObject {
                put("transactions", ledger.transactions.size)
                put("categories", ledger.categories.size)
                put("budgets", ledger.budgets.size)
            }.toString()
        }
        "validate-workspace" -> {
            expect(args, 1)
            val workspace = loadWorkspace(args[1])
            buildJsonObject {
                put("accounts", workspace.accounts.size)
                put("books", workspace.books.size)
                put("rules", workspace.automation.rules.size)
                put("inbox_items", workspace.automation.items.size)
            }.toString()
        }
        "export-csv" -> {
            expect(args, 2)
            writeNew(args[2], TransactionCsvCodec().encode(loadLedger(args[1]).transactions))
            null
        }
        "export-book" -> {
            expect(args, 4)
            val workspace = loadWorkspace(args[1])
            val ledger = loadLedger(args[2])
            writeNew(args[4], ledgerCodec.encode(analytics.scopedSnapshot(workspace, ledger, args[3])))
            null
        }
        "preview-statement", "import-statement" -> statement(args)
        "audit" -> {
            expect(args, 4)
            audit(FinanceWorkspaceCodec.empty(), loadLedger(args[1]), null, args[2], args[3], args[4])
        }
        "audit-book" -> {
            expect(args, 6)
            audit(loadWorkspace(args[1]), loadLedger(args[2]), args[3], args[4], args[5], args[6])
        }
        "inbox", "rules-preview" -> review(args)
        else -> throw IllegalArgumentException("未知命令。\n$usage")
    }

    private fun statement(args: List<String>): String {
        val writeBackup = args.first() == "import-statement"
        if (writeBackup) expect(args, 2, 3) else expect(args, 1, 2)
        val rate = args.getOrNull(if (writeBackup) 3 else 2)
        var preview = TableTransactionImporter.decode(
            readBytes(args[1], TableTransactionImporter.MAX_FILE_BYTES),
            DefaultCategories.all,
            System.currentTimeMillis(),
        )
        if (rate != null) {
            require(preview.requiresCurrencyConversion) { "仅支持为非 CNY 账单指定汇率。" }
            preview = TableTransactionImporter.convertToCny(preview, rate)
        }
        if (writeBackup) {
            require(!preview.requiresCurrencyConversion) { "请输入 1 ${preview.sourceCurrencyCode} 对应的 CNY 金额。" }
            require(preview.transactions.isNotEmpty()) { "账单没有可导入的流水。" }
            val backup = FinanceDataSnapshot(preview.transactions, DefaultCategories.all, emptyList())
            writeNew(args[2], ledgerCodec.encode(backup))
        }
        return buildJsonObject {
            put("format", preview.format)
            put("transactions", preview.transactions.size)
            put("skipped_rows", preview.skippedRows)
            put("duplicate_rows", preview.duplicateRows)
            put("invalid_rows", preview.invalidRows)
            put("requires_currency_conversion", preview.requiresCurrencyConversion)
            preview.sourceCurrencyCode?.let { put("source_currency", it) }
            preview.appliedExchangeRate?.let { put("exchange_rate", it) }
            put("issues", Json.encodeToJsonElement(preview.issueMessages))
        }.toString()
    }

    private fun audit(
        workspace: FinanceWorkspaceState,
        ledger: FinanceDataSnapshot,
        bookId: String?,
        start: String,
        end: String,
        question: String,
    ): String {
        checkBook(workspace, bookId)
        val transactions = analytics.transactionsForBook(workspace, ledger.transactions, bookId)
        val answer = FinanceAudit().answer(
            question, transactions, ledger.categories,
            Instant.parse(start).toEpochMilliseconds(), Instant.parse(end).toEpochMilliseconds(),
        )
        return auditJson(answer)
    }

    private fun review(args: List<String>): String {
        expect(args, 2, 3)
        val workspace = loadWorkspace(args[1])
        val ledger = loadLedger(args[2])
        val bookId = args.getOrNull(3)
        checkBook(workspace, bookId)
        val transactions = analytics.transactionsForBook(workspace, ledger.transactions, bookId)
        if (args.first() == "inbox") {
            return Json.encodeToString(automation.reviewItems(workspace.automation, bookId, transactions))
        }
        val includedIds = transactions.mapTo(hashSetOf()) { it.id }
        val originalRows = ledger.transactions.filter { it.id in includedIds }
        return buildJsonArray {
            for (suggestion in automation.ruleSuggestions(workspace.automation, bookId, originalRows, ledger.categories)) {
                add(buildJsonObject {
                    put("transaction_id", suggestion.transaction.id)
                    put("updated_at_epoch_millis", suggestion.transaction.updatedAt)
                    put("category_id", suggestion.categoryId)
                    put("rule_id", suggestion.ruleId)
                })
            }
        }.toString()
    }

    private fun loadLedger(file: String) = ledgerCodec.decode(readBytes(file, maxJsonBytes).decodeToString())

    private fun loadWorkspace(file: String) = FinanceWorkspaceCodec.decode(readBytes(file, maxJsonBytes).decodeToString())
}

private fun auditJson(answer: FinanceAuditAnswer): String = buildJsonObject {
    put("title", answer.title)
    put("matched_count", answer.matchedCount)
    put("omitted_source_count", answer.omittedSourceCount)
    put("start_epoch_millis", answer.startEpochMillis)
    put("end_epoch_millis", answer.endEpochMillis)
    put("facts", buildJsonArray {
        for (fact in answer.facts) add(buildJsonObject {
            put("id", fact.id)
            put("text", fact.text)
            put("source_references", Json.encodeToJsonElement(fact.sourceReferences))
        })
    })
    put("sources", buildJsonArray {
        for ((reference, transaction) in answer.sources) add(buildJsonObject {
            put("reference", reference)
            put("transaction_id", transaction.id)
            put("amount_minor", transaction.amount)
            put("date_time_epoch_millis", transaction.dateTime.toEpochMilliseconds())
        })
    })
}.toString()

private fun checkBook(workspace: FinanceWorkspaceState, bookId: String?) {
    require(bookId == null || workspace.books.any { it.id == bookId }) { "找不到账本 $bookId。" }
}

private fun expect(args: List<String>, minimum: Int, maximum: Int = minimum) {
    require(args.size - 1 in minimum..maximum) { "命令参数数量不正确。\n$usage" }
}

private fun readBytes(file: String, limit: Int): ByteArray = Files.newInputStream(Path.of(file)).use { input ->
    input.readNBytes(limit + 1).also { require(it.size <= limit) { "文件超出读取上限（$limit 字节）。" } }
}

private fun writeNew(file: String, contents: String) {
    Files.writeString(Path.of(file), contents, CREATE_NEW, WRITE)
}

private const val maxJsonBytes = 64 * 1024 * 1024

private val usage = """
    FinanceOS workspace CLI
    validate-backup <ledger.json>
    validate-workspace <workspace.json>
    export-csv <ledger.json> <new.csv>
    export-book <workspace.json> <ledger.json> <book-id> <new.json>
    preview-statement <statement> [cny-rate]
    import-statement <statement> <new-ledger.json> [cny-rate]
    audit <ledger.json> <start-ISO> <end-ISO> <question>
    audit-book <workspace.json> <ledger.json> <book-id> <start-ISO> <end-ISO> <question>
    inbox <workspace.json> <ledger.json> [book-id]
    rules-preview <workspace.json> <ledger.json> [book-id]
""".trimIndent()
