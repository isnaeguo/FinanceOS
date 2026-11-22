package com.financeos.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import com.financeos.app.ui.viewmodel.MAX_PASTE_CHARACTERS

/** Explicit native shares and launcher shortcuts handled through existing preview and entry routes. */
internal sealed interface FinanceEntry {
    data object AddTransaction : FinanceEntry
    data object OpenWorkspace : FinanceEntry
    data class StatementText(val content: String) : FinanceEntry
    data class StatementFile(val uri: Uri) : FinanceEntry
    data class ReceiptImage(val uri: Uri) : FinanceEntry
    data class Unsupported(val message: String) : FinanceEntry

    companion object {
        private const val ADD_ACTION = "com.financeos.app.action.ADD_TRANSACTION"
        private const val WORKSPACE_ACTION = "com.financeos.app.action.OPEN_WORKSPACE"

        fun fromIntent(intent: Intent): FinanceEntry? = when (intent.action) {
            ADD_ACTION -> AddTransaction
            WORKSPACE_ACTION -> OpenWorkspace
            Intent.ACTION_SEND -> readShare(intent)
            else -> null
        }

        private fun readShare(intent: Intent): FinanceEntry {
            val uri = streamUri(intent)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri != null) {
                if (uri.scheme != "content") return Unsupported("无法读取共享文件，请从系统文件或相册分享。")
                return if (intent.type?.startsWith("image/") == true) ReceiptImage(uri) else StatementFile(uri)
            }
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            return when {
                text.isNullOrBlank() -> Unsupported("这次分享没有可用的账单或票据。")
                text.length > MAX_PASTE_CHARACTERS -> Unsupported("共享文本过长，请改用文件导入。")
                else -> StatementText(text.toString())
            }
        }

        @Suppress("DEPRECATION")
        private fun streamUri(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}
