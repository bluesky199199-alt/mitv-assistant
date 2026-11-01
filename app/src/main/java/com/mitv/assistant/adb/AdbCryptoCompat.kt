package com.mitv.assistant.adb

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * ADB RSA 认证加密工具。
 *
 * 移植自开源库 tananaev/adblib（MIT）中的同名实现，格式必须与 Android adbd 一致：
 * - 公钥 payload：524 字节小端 RSAPublicKey 结构（len/n0inv/n[64]/rr[64]/exponent），
 *   Base64 编码后接 " user@host\0"
 * - 签名：手工构造 PKCS#1 v1.5 SHA-1 padding 后做 RSA/ECB/NoPadding 运算
 */
object AdbCryptoCompat {

    const val KEY_LENGTH_BITS = 2048
    private const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8   // 256
    private const val KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4  // 64

    /** 签名用的 PKCS#1 v1.5 / SHA-1 前缀（256-20=236 字节） */
    private fun sha1Pkcs1Prefix(): ByteArray {
        val digestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b.toByte(), 0x0e.toByte(), 0x03,
            0x02, 0x1a.toByte(), 0x05, 0x00, 0x04, 0x14
        )
        val out = ByteArray(KEY_LENGTH_BYTES - 20)
        var i = 0
        out[i++] = 0x00
        out[i++] = 0x01
        // FF 填充长度 = 256 - 3(00 01 00) - 15(DigestInfo头) - 20(SHA1) = 218
        repeat(KEY_LENGTH_BYTES - 3 - digestInfo.size - 20) { out[i++] = 0xFF.toByte() }
        out[i++] = 0x00
        digestInfo.copyInto(out, i)
        return out
    }

    fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(KEY_LENGTH_BITS)
        return gen.generateKeyPair()
    }

    fun keyPairFrom(persistedPublicB64: String?, persistedPrivateB64: String?): KeyPair? {
        if (persistedPublicB64.isNullOrBlank() || persistedPrivateB64.isNullOrBlank()) return null
        return try {
            val factory = KeyFactory.getInstance("RSA")
            val pub = factory.generatePublic(X509EncodedKeySpec(Base64.decode(persistedPublicB64, Base64.NO_WRAP)))
            val priv = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(persistedPrivateB64, Base64.NO_WRAP)))
            KeyPair(pub, priv)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeKeyPair(keyPair: KeyPair): Pair<String, String> =
        Pair(
            Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP),
            Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP),
        )

    /** 对 adbd 下发的 20 字节 token 做签名 */
    fun signToken(keyPair: KeyPair, token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        // ADB AUTH token 本身就是 20 字节 SHA-1 挑战值。
        // PKCS#1 v1.5 DigestInfo 前缀已经包含 SHA-1 标识；不要再次对 token 求摘要。
        cipher.update(sha1Pkcs1Prefix())
        return cipher.doFinal(token)
    }

    /**
     * 生成 AUTH_PUBLICKEY 的完整 payload（Base64 结构 + " unknown@unknown" + NUL）。
     */
    fun adbPublicKeyPayload(keyPair: KeyPair): ByteArray {
        val converted = convertToAdbFormat(keyPair.public as RSAPublicKey)
        val line = Base64.encodeToString(converted, Base64.NO_WRAP) + " unknown@unknown"
        return (line + '\u0000').toByteArray(Charsets.UTF_8)
    }

    /**
     * 标准 RSAPublicKey 转 ADB 格式（524 字节，全小端）。
     */
    private fun convertToAdbFormat(pubkey: RSAPublicKey): ByteArray {
        val r32 = BigInteger.ONE.shiftLeft(32)
        var n: BigInteger = pubkey.modulus
        val r = BigInteger.ONE.shiftLeft(KEY_LENGTH_WORDS * 32)
        var rr = r.multiply(r).mod(n) // R^2 mod n

        val n0inv = n.mod(r32).modInverse(r32)

        val myN = IntArray(KEY_LENGTH_WORDS)
        val myRr = IntArray(KEY_LENGTH_WORDS)
        for (i in 0 until KEY_LENGTH_WORDS) {
            rr.divideAndRemainder(r32).let { rr = it[0]; myRr[i] = it[1].intValueExact() }
            n.divideAndRemainder(r32).let { n = it[0]; myN[i] = it[1].intValueExact() }
        }

        val buf = java.nio.ByteBuffer.allocate(4 + 4 + 256 + 256 + 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(KEY_LENGTH_WORDS)
        // n0inv = -n^-1 mod 2^32，需按无符号低 32 位写入（取相反数后取低 32 位的补码表示）
        buf.putInt(low32Signed(n0inv.negate()))
        for (v in myN) buf.putInt(v)
        for (v in myRr) buf.putInt(v)
        buf.putInt(low32Signed(pubkey.publicExponent))
        return buf.array()
    }

    /** 取 BigInteger 低 32 位并按有符号 Int 解释（不抛异常，等价于 Java 的 intValue 截断语义） */
    private fun low32Signed(v: java.math.BigInteger): Int {
        val m = v.and(java.math.BigInteger.valueOf(0xFFFFFFFFL))
        val twoPow31 = java.math.BigInteger.ONE.shiftLeft(31)
        return if (m >= twoPow31) m.subtract(java.math.BigInteger.ONE.shiftLeft(32)).intValueExact()
        else m.intValueExact()
    }
}
