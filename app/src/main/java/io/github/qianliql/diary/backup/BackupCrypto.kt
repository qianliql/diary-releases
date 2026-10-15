package io.github.qianliql.diary.backup

import java.io.*
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, streaming backup envelope. PBKDF2 parameters are fixed per format version. */
object BackupCrypto {
    private val magic = "DIARYBK1".toByteArray(Charsets.US_ASCII)
    private const val iterations = 600_000
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "备份密码至少需要 8 个字符" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val header = magic + salt + iv
        output.write(header)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv)); updateAAD(header)
        }
        CipherOutputStream(output, cipher).use { input.copyTo(it) }
    }
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val header = ByteArray(36)
        DataInputStream(input).readFully(header)
        require(header.copyOfRange(0, 8).contentEquals(magic)) { "不是受支持的日记备份" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(password, header.copyOfRange(8, 24)), GCMParameterSpec(128, header.copyOfRange(24, 36))); updateAAD(header)
        }
        // The caller must decrypt into staging and only import after the authentication tag passes.
        CipherInputStream(input, cipher).use { it.copyTo(output) }
    }
}
