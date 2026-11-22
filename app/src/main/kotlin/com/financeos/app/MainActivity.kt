package com.financeos.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financeos.app.navigation.FinanceOsNavigation
import com.financeos.app.ui.theme.FinanceOsTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val entry = MutableStateFlow<FinanceEntry?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null || savedInstanceState.getBoolean(PENDING_ENTRY)) receive(intent)
        enableEdgeToEdge()
        setContent {
            val pendingEntry by entry.collectAsStateWithLifecycle()
            FinanceOsTheme {
                FinanceOsNavigation(
                    entry = pendingEntry,
                    onEntryHandled = { handled -> entry.compareAndSet(handled, null) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(PENDING_ENTRY, entry.value != null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        entry.value = try {
            FinanceEntry.fromIntent(intent)
        } catch (_: RuntimeException) {
            FinanceEntry.Unsupported("无法读取共享内容。")
        }
    }
    private companion object {
        const val PENDING_ENTRY = "financeos.pending_entry"
    }
}
