package com.financeos.shared.lansync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.Throws

/** JVM / Android 实现：javax.crypto（PBKDF2WithHmacSHA256 + AES/CBC/PKCS5Padding + HmacSHA256）。 */
actual object LanSyncCrypto {
    private val secureRandom = SecureRandom()

    actual fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    @Throws(LanSyncException::class)
    actual fun deriveKey(code: String, salt: ByteArray): ByteArray = try {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(code.toCharArray(), salt, LanSyncSpec.KDF_ITERATIONS, LanSyncSpec.KEY_BYTES * 8)
        factory.generateSecret(spec).encoded
    } catch (error: Exception) {
        throw LanSyncException("密钥派生失败。", error)
    }

    @Throws(LanSyncException::class)
    actual fun encrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray = try {
        require(iv.size == LanSyncSpec.IV_BYTES) { "IV 长度必须为 ${LanSyncSpec.IV_BYTES}" }
        val secret = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)
        envelope(iv, mac(key, iv, ciphertext, aad), ciphertext)
    } catch (error: LanSyncException) {
        throw error
    } catch (error: Exception) {
        throw LanSyncException("加密失败。", error)
    }

    @Throws(LanSyncException::class)
    actual fun decrypt(key: ByteArray, envelope: ByteArray, aad: ByteArray): ByteArray = try {
        val (iv, expectedMac, ciphertext) = splitEnvelope(envelope)
        val actualMac = mac(key, iv, ciphertext, aad)
        if (!constantTimeEquals(expectedMac, actualMac)) {
            throw LanSyncException("配对码错误或数据已损坏。")
        }
        val secret = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
        cipher.doFinal(ciphertext)
    } catch (error: LanSyncException) {
        throw error
    } catch (error: Exception) {
        throw LanSyncException("配对码错误或数据已损坏。", error)
    }

    private fun mac(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(iv)
        mac.update(ciphertext)
        mac.update(aad)
        return mac.doFinal()
    }

    private fun envelope(iv: ByteArray, mac: ByteArray, ciphertext: ByteArray): ByteArray {
        val total = iv.size + mac.size + ciphertext.size
        val out = ByteArray(total)
        iv.copyInto(out, 0)
        mac.copyInto(out, iv.size)
        ciphertext.copyInto(out, iv.size + mac.size)
        return out
    }

    private fun splitEnvelope(envelope: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val macOffset = LanSyncSpec.IV_BYTES
        val cipherOffset = macOffset + LanSyncSpec.MAC_BYTES
        if (envelope.size < cipherOffset) throw LanSyncException("配对码错误或数据已损坏。")
        val iv = envelope.copyOfRange(0, macOffset)
        val mac = envelope.copyOfRange(macOffset, cipherOffset)
        val ciphertext = envelope.copyOfRange(cipherOffset, envelope.size)
        return Triple(iv, mac, ciphertext)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }
}
