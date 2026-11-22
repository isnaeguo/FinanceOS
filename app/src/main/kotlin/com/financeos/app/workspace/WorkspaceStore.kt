package com.financeos.app.workspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.financeos.shared.workspace.FinanceWorkspaceCodec
import com.financeos.shared.workspace.FinanceWorkspaceState
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class WorkspaceStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "finance-workspace.json"))
    private val codec = FinanceWorkspaceCodec

    suspend fun load(): FinanceWorkspaceState = withContext(Dispatchers.IO) {
        try {
            val bytes = file.openRead().use { it.readPrefix(MAX_WORKSPACE_BYTES + 1) }
            require(bytes.size <= MAX_WORKSPACE_BYTES) { "工作台文件过大。" }
            codec.decode(bytes.toString(UTF_8))
        } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            FinanceWorkspaceState()
        }
    }

    suspend fun save(state: FinanceWorkspaceState) = withContext(Dispatchers.IO) {
        val bytes = codec.encode(state).toByteArray(UTF_8)
        require(bytes.size <= MAX_WORKSPACE_BYTES) { "工作台文件过大。" }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val MAX_WORKSPACE_BYTES = 16 * 1024 * 1024
    }
}

/** App-private copies let attachments survive a photo picker's short-lived URI grant. */
internal class ReceiptVault(context: Context) {
    private val resolver = context.contentResolver
    private val directory = File(context.noBackupFilesDir, "finance-receipts")
    private val captureDirectory = File(context.cacheDir, "receipt-capture")

    fun createCameraCapture(): File {
        captureDirectory.mkdirs()
        return File.createTempFile("receipt-", ".jpg", captureDirectory)
    }

    fun receiptUri(context: Context, file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    suspend fun save(id: String, uri: Uri): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val dest = file(id)
        try {
            val source = resolver.openInputStream(uri) ?: error("无法读取所选票据。")
            source.use { input ->
                dest.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var byteCount = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(byteCount + count <= MAX_RECEIPT_BYTES) { "票据图片不能超过 12 MB。" }
                        output.write(buffer, 0, count)
                        byteCount += count
                    }
                    require(byteCount != 0) { "票据图片为空。" }
                }
            }
            require(isReadableImage(dest)) { "所选内容不是可读取的票据图片。" }
            dest
        } catch (error: Exception) {
            dest.delete()
            throw error
        }
    }

    fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "票据引用无效。" }
        return File(directory, "$id.image")
    }

    suspend fun preview(id: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = file(id)
        val options = bounds(file)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
        val size = maxOf(options.outWidth, options.outHeight)
        var sampleSize = 1
        while (size / sampleSize > 1_600) sampleSize *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
        BitmapFactory.decodeFile(file.absolutePath, options)
    }

    suspend fun readText(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { it.readPrefix(MAX_RECEIPT_BYTES + 1) }
            ?: throw IllegalArgumentException("无法读取票据图片。")
        require(bytes.isNotEmpty() && bytes.size <= MAX_RECEIPT_BYTES) { "票据图片为空或超过 12 MB。" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "所选内容不是可读取的票据图片。" }
        val edge = maxOf(options.outWidth, options.outHeight)
        var sample = 1
        while (edge / sample > 2_400) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("无法解码票据图片。")
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        suspendCancellableCoroutine { continuation ->
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, rotation)).addOnCompleteListener { task ->
                    try {
                        if (task.isSuccessful) continuation.resume(task.result.text.take(2_000))
                        else continuation.resumeWithException(IllegalArgumentException("识别失败，可以手动填写票据备注。"))
                    } finally {
                        recognizer.close()
                        bitmap.recycle()
                    }
                }
            } catch (error: Exception) {
                recognizer.close()
                bitmap.recycle()
                continuation.resumeWithException(error)
            }
        }
    }

    private fun isReadableImage(file: File): Boolean =
        bounds(file).let { it.outWidth > 0 && it.outHeight > 0 }

    private fun bounds(file: File): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options
    }

    private companion object {
        const val MAX_RECEIPT_BYTES = 12 * 1024 * 1024
    }
}

private fun InputStream.readPrefix(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count <= 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

class ReceiptFileProvider : androidx.core.content.FileProvider()
