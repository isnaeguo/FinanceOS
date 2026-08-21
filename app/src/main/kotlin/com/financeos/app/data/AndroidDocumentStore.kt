package com.financeos.app.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 通过 Android Storage Access Framework 读写用户明确选择的文档。 */
internal class AndroidDocumentStore(context: Context) {
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver

    suspend fun writeText(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        val output = contentResolver.openOutputStream(uri, "wt")
            ?: error("无法打开所选文件。")
        OutputStreamWriter(output, StandardCharsets.UTF_8).buffered().use { writer ->
            writer.write(content)
        }
    }

    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val input = contentResolver.openInputStream(uri)
            ?: error("无法读取所选文件。")
        InputStreamReader(input, StandardCharsets.UTF_8).buffered().use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(READ_BUFFER_SIZE)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                result.append(buffer, 0, count)
                require(result.length <= MAX_DOCUMENT_CHARACTERS) {
                    "文件过大，当前版本最多读取 20 MB。"
                }
            }
            result.toString()
        }
    }
}

private const val READ_BUFFER_SIZE = 8 * 1024
private const val MAX_DOCUMENT_CHARACTERS = 20 * 1024 * 1024
