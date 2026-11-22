package com.financeos.shared.workspace

import com.financeos.shared.domain.model.TransactionType
import kotlinx.serialization.Serializable

/**
 * Metadata alongside the existing Room ledger. Transaction IDs refer to original ledger rows.
 * The core ledger and its backup format continue to record integer CNY minor units.
 */
@Serializable
data class FinanceWorkspaceState(
    val accounts: List<WorkspaceAccount> = emptyList(),
    val books: List<WorkspaceBook> = emptyList(),
    val assignments: List<WorkspaceAssignment> = emptyList(),
    val links: List<WorkspaceLink> = emptyList(),
    val checkpoints: List<WorkspaceCheckpoint> = emptyList(),
    val plans: List<WorkspacePlan> = emptyList(),
    val automation: WorkspaceAutomationState = WorkspaceAutomationState(),
)

@Serializable
data class WorkspaceAccount(
    val id: String,
    val name: String,
    val currency: String,
    val openingBalanceMinor: Long,
)

@Serializable
data class WorkspaceBook(val id: String, val name: String, val currency: String)

@Serializable
data class WorkspaceAssignment(
    val transactionId: String,
    val accountId: String? = null,
    val bookId: String? = null,
    val originalCurrency: String? = null,
    val originalAmountMinor: Long? = null,
    val splits: List<WorkspaceSplit> = emptyList(),
)

/** Split amounts are CNY allocations and must add up to the booked amount. */
@Serializable
data class WorkspaceSplit(val bookId: String, val amountMinor: Long)

@Serializable
enum class WorkspaceLinkKind { DUPLICATE, TRANSFER, REFUND }

/** Duplicate → retained row, transfer expense → income, or refund income → original expense. */
@Serializable
data class WorkspaceLink(val sourceId: String, val targetId: String, val kind: WorkspaceLinkKind)

@Serializable
data class WorkspaceCheckpoint(
    val id: String,
    val accountId: String,
    val asOfEpochMillis: Long,
    val statementBalanceMinor: Long,
)

@Serializable
enum class WorkspaceRepeat { ONCE, WEEKLY, MONTHLY }

/** The first day and recurrence are local civil dates expressed as epoch days. */
@Serializable
data class WorkspacePlan(
    val id: String,
    val name: String,
    val amountMinor: Long,
    val type: TransactionType,
    val firstEpochDay: Long,
    val repeat: WorkspaceRepeat,
    val occurrences: Int,
    val accountId: String? = null,
    val bookId: String? = null,
    val scenario: String? = null,
)

data class WorkspaceSummary(
    val accountBalances: List<WorkspaceAccountBalance>,
    val bookBalances: List<WorkspaceBookBalance>,
    val reconciliations: List<WorkspaceReconciliation>,
    val unassignedCount: Int,
    val unresolvedLinkCount: Int,
)

data class WorkspaceAccountBalance(
    val accountId: String,
    val name: String,
    val currency: String,
    val balanceMinor: Long,
    val transactionCount: Int,
    val unvaluedCount: Int,
)

data class WorkspaceBookBalance(
    val bookId: String,
    val name: String,
    val currency: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val unvaluedCount: Int,
)

data class WorkspaceReconciliation(
    val checkpointId: String,
    val accountId: String,
    val asOfEpochMillis: Long,
    val statementBalanceMinor: Long,
    val ledgerBalanceMinor: Long,
    val differenceMinor: Long,
    val unvaluedCount: Int,
)

data class WorkspaceForecast(
    val openingBalanceMinor: Long,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val closingBalanceMinor: Long,
    val lowBalanceMinor: Long,
    val points: List<WorkspaceForecastPoint>,
)

data class WorkspaceForecastPoint(
    val epochDay: Long,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val balanceMinor: Long,
)

data class WorkspaceNetWorthPoint(val asOfEpochMillis: Long, val balanceMinor: Long, val missingOriginalCount: Int)
