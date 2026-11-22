package com.financeos.shared.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Independent versioning keeps existing FinanceOS ledger backups readable without migration. */
object FinanceWorkspaceCodec {
    private const val FORMAT = "financeos-workspace"
    private const val VERSION = 1
    private const val MAX_CHARS = 32 * 1024 * 1024
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    fun empty(): FinanceWorkspaceState = FinanceWorkspaceState()

    @Throws(IllegalArgumentException::class)
    fun encode(state: FinanceWorkspaceState): String {
        validateWorkspaceState(state)
        val text = json.encodeToString(WorkspaceDocument(FORMAT, VERSION, state))
        require(text.length <= MAX_CHARS) { "工作区数据过大。" }
        return text
    }

    @Throws(IllegalArgumentException::class)
    fun decode(text: String): FinanceWorkspaceState {
        require(text.length <= MAX_CHARS) { "工作区文件过大。" }
        val document = json.decodeFromString<WorkspaceDocument>(text.removePrefix("\uFEFF"))
        require(document.format == FORMAT) { "文件不是 FinanceOS 工作区。" }
        require(document.schemaVersion == VERSION) { "暂不支持该 FinanceOS 工作区版本。" }
        validateWorkspaceState(document.state)
        return document.state
    }
}

@Serializable
private data class WorkspaceDocument(
    val format: String,
    @SerialName("schema_version") val schemaVersion: Int,
    val state: FinanceWorkspaceState,
)
