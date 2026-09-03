@file:OptIn(ExperimentalForeignApi::class)

package com.financeos.shared.lansync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.CCCryptorStatus
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.posix.arc4random_buf
import kotlin.Throws

/**
 * Apple 实现：CommonCrypto（CCKeyDerivationPBKDF / CCCrypt / CCHmac）。
 *
 * 与 JVM 实现的信封与 MAC 顺序完全一致：`iv(16) ‖ mac(32) ‖ ciphertext`，MAC 覆盖 IV‖密文‖AAD，
 * 保证两端密文逐字节一致。随机字节来自系统 arc4random_buf。
 */
@OptIn(ExperimentalForeignApi::class)
actual object LanSyncCrypto {
    actual fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        bytes.usePinned { pinned ->
            arc4random_buf(pinned.addressOf(0), size.toULong())
        }
        return bytes
    }

    @Throws(LanSyncException::class)
    actual fun deriveKey(code: String, salt: ByteArray): ByteArray = memScoped {
        require(salt.size == LanSyncSpec.SALT_BYTES) { "salt 长度必须为 ${LanSyncSpec.SALT_BYTES}" }
        val key = allocArray<UByteVar>(LanSyncSpec.KEY_BYTES)
        val saltBuffer = allocArray<UByteVar>(salt.size)
        for (index in salt.indices) {
            saltBuffer[index] = salt[index].toUByte()
        }
        val status = CCKeyDerivationPBKDF(
            kCCPBKDF2,
            code,
            code.length.toULong(),
            saltBuffer,
            salt.size.toULong(),
            kCCPRFHmacAlgSHA256,
            LanSyncSpec.KDF_ITERATIONS.toUInt(),
            key,
            LanSyncSpec.KEY_BYTES.toULong(),
        )
        if (status != kCCSuccess) throw LanSyncException("密钥派生失败（CommonCrypto $status）。")
        ByteArray(LanSyncSpec.KEY_BYTES) { index -> key[index].toByte() }
    }

    @Throws(LanSyncException::class)
    actual fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(iv.size == LanSyncSpec.IV_BYTES) { "IV 长度必须为 ${LanSyncSpec.IV_BYTES}" }
        val ciphertext = crypt(
            operation = kCCEncrypt,
            key = key,
            iv = iv,
            input = plaintext,
            outputSize = paddedSize(plaintext.size),
        )
        val mac = mac(key, iv, ciphertext, aad)
        return envelope(iv, mac, ciphertext)
    }

    @Throws(LanSyncException::class)
    actual fun decrypt(key: ByteArray, envelope: ByteArray, aad: ByteArray): ByteArray {
        val (iv, expectedMac, ciphertext) = splitEnvelope(envelope)
        val actualMac = mac(key, iv, ciphertext, aad)
        if (!constantTimeEquals(expectedMac, actualMac)) {
            throw LanSyncException("配对码错误或数据已损坏。")
        }
        return crypt(
            operation = kCCDecrypt,
            key = key,
            iv = iv,
            input = ciphertext,
            outputSize = ciphertext.size,
        )
    }

    private fun paddedSize(inputSize: Int): Int = inputSize + (LanSyncSpec.IV_BYTES - (inputSize % LanSyncSpec.IV_BYTES))

    /** AES-CBC（PKCS7）加密/解密；失败抛 [LanSyncException]。 */
    private fun crypt(
        operation: UInt,
        key: ByteArray,
        iv: ByteArray,
        input: ByteArray,
        outputSize: Int,
    ): ByteArray = memScoped {
        val output = allocArray<UByteVar>(outputSize)
        val moved = allocArray<ULongVar>(1)
        val status: CCCryptorStatus = key.usePinned { keyPinned ->
            iv.usePinned { ivPinned ->
                input.usePinned { inputPinned ->
                    CCCrypt(
                        operation,
                        kCCAlgorithmAES,
                        kCCOptionPKCS7Padding,
                        keyPinned.addressOf(0),
                        key.size.toULong(),
                        ivPinned.addressOf(0),
                        inputPinned.addressOf(0),
                        input.size.toULong(),
                        output,
                        outputSize.toULong(),
                        moved,
                    )
                }
            }
        }
        if (status != kCCSuccess) {
            throw LanSyncException("加密/解密失败（CommonCrypto $status）。")
        }
        val written = moved[0].toInt()
        if (written <= 0 || written > outputSize) throw LanSyncException("加密/解密失败（长度异常）。")
        ByteArray(written) { index -> output[index].toByte() }
    }

    private fun mac(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray = memScoped {
        val output = allocArray<UByteVar>(LanSyncSpec.MAC_BYTES)
        key.usePinned { keyPinned ->
            // CommonCrypto CCHmac 单次调用只接受一段数据，MAC 覆盖 IV‖密文‖AAD 需分段更新，
            // 因此先拼接后统一签名（JVM 侧 update 语义等价）。
            val macInput = ByteArray(iv.size + ciphertext.size + aad.size)
            iv.copyInto(macInput, 0)
            ciphertext.copyInto(macInput, iv.size)
            aad.copyInto(macInput, iv.size + ciphertext.size)
            macInput.usePinned { inputPinned ->
                CCHmac(
                    kCCHmacAlgSHA256,
                    keyPinned.addressOf(0),
                    key.size.toULong(),
                    inputPinned.addressOf(0),
                    macInput.size.toULong(),
                    output,
                )
            }
        }
        ByteArray(LanSyncSpec.MAC_BYTES) { index -> output[index].toByte() }
    }

    private fun envelope(iv: ByteArray, mac: ByteArray, ciphertext: ByteArray): ByteArray {
        val out = ByteArray(iv.size + mac.size + ciphertext.size)
        iv.copyInto(out, 0)
        mac.copyInto(out, iv.size)
        ciphertext.copyInto(out, iv.size + mac.size)
        return out
    }

    private fun splitEnvelope(envelope: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val macOffset = LanSyncSpec.IV_BYTES
        val cipherOffset = macOffset + LanSyncSpec.MAC_BYTES
        if (envelope.size < cipherOffset) throw LanSyncException("配对码错误或数据已损坏。")
        return Triple(
            envelope.copyOfRange(0, macOffset),
            envelope.copyOfRange(macOffset, cipherOffset),
            envelope.copyOfRange(cipherOffset, envelope.size),
        )
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }
}
