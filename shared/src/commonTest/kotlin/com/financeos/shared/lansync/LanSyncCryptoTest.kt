package com.financeos.shared.lansync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 跨端一致性固定向量：给定 code/salt/iv/aad，JVM 与 Native 两个 actual 必须输出与下述
 * 十六进制逐字节一致（向量由 JVM 标准 javax.crypto 首次计算，并经 commonTest 锁死两端）。
 */
class LanSyncCryptoTest {
    private fun unhex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun deriveKeyMatchesFixedVector() {
        val key = LanSyncCrypto.deriveKey(
            code = "K7M2QX4T9A",
            salt = unhex("000102030405060708090a0b0c0d0e0f"),
        )
        assertEquals("62285ec80eaedf13701a86ae4c05e0e448bbaf757c39773045bf37128c194cb4", hex(key))
    }

    @Test
    fun encryptMatchesFixedVectorAndRoundTrips() {
        val key = LanSyncCrypto.deriveKey("K7M2QX4T9A", unhex("000102030405060708090a0b0c0d0e0f"))
        val iv = unhex("101112131415161718191a1b1c1d1e1f")
        val aad = "2".encodeToByteArray()
        val plaintext = "{\"proto\":2,\"kind\":\"snapshot_v2\",\"body\":\"hello\"}".encodeToByteArray()
        val envelope = LanSyncCrypto.encrypt(key, iv, plaintext, aad)
        assertEquals(
            "101112131415161718191a1b1c1d1e1f" +
                "2a103e5d2bee4b88cba8f5621c51d9497e8f9a3850151a1d1e9aa5761bccb09694" +
                "fddb621fd1f49bc8cbb893eff0e20d9a24c8fa295b5510479ba90de84db918545c0a8545f4e7a0aafe3e23137f3ed6",
            hex(envelope),
        )
        assertTrue(LanSyncCrypto.decrypt(key, envelope, aad).contentEquals(plaintext))
    }

    @Test
    fun wrongPairingCodeFails() {
        val key = LanSyncCrypto.deriveKey("K7M2QX4T9A", unhex("000102030405060708090a0b0c0d0e0f"))
        val wrongKey = LanSyncCrypto.deriveKey("AAAAAAAAAA", unhex("000102030405060708090a0b0c0d0e0f"))
        val envelope = LanSyncCrypto.encrypt(
            key,
            unhex("101112131415161718191a1b1c1d1e1f"),
            "data".encodeToByteArray(),
            "2".encodeToByteArray(),
        )
        assertFailsWith<LanSyncException> {
            LanSyncCrypto.decrypt(wrongKey, envelope, "2".encodeToByteArray())
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val key = LanSyncCrypto.deriveKey("K7M2QX4T9A", unhex("000102030405060708090a0b0c0d0e0f"))
        val envelope = LanSyncCrypto.encrypt(
            key,
            unhex("101112131415161718191a1b1c1d1e1f"),
            "data".encodeToByteArray(),
            "2".encodeToByteArray(),
        )
        val tampered = envelope.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        assertFailsWith<LanSyncException> {
            LanSyncCrypto.decrypt(key, tampered, "2".encodeToByteArray())
        }
    }

    @Test
    fun malformedEnvelopeFails() {
        val key = LanSyncCrypto.deriveKey("K7M2QX4T9A", unhex("000102030405060708090a0b0c0d0e0f"))
        assertFailsWith<LanSyncException> {
            LanSyncCrypto.decrypt(key, ByteArray(8), ByteArray(0))
        }
    }

    @Test
    fun pairingCodeFormatIsValidated() {
        repeat(20) {
            val code = LanPairing.generate()
            assertEquals(LanPairing.CODE_LENGTH, code.length)
            assertTrue(LanPairing.isValid(code))
        }
        assertTrue(!LanPairing.isValid("K7M2QX4T9A0"))
        assertTrue(!LanPairing.isValid("K7M2QX4T9O")) // 字母表不含 O
        assertTrue(!LanPairing.isValid("k7m2qx4t9a")) // 必须大写
    }

    @Test
    fun timestampPolicyRejectsStale() {
        val now = 1_786_400_000_000L
        assertTrue(LanSyncPolicy.isTimestampFresh(now, now))
        assertTrue(LanSyncPolicy.isTimestampFresh(now - 5 * 60 * 1000L, now))
        assertTrue(!LanSyncPolicy.isTimestampFresh(now - 5 * 60 * 1000L - 1, now))
        assertTrue(!LanSyncPolicy.isTimestampFresh(now + 6 * 60 * 1000L, now))
    }
}
