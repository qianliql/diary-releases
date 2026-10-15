package io.github.qianliql.diary.backup

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated 64 KiB frames bound Android AEAD provider memory use. */
object BackupCrypto {
    private val magic = "DIARYBK2".toByteArray(Charsets.US_ASCII)
    private val legacyMagic = "DIARYBK1".toByteArray(Charsets.US_ASCII)
    private const val iterations = 600_000
    private const val chunkSize = 64 * 1024
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    private fun frame(cipher: Cipher, mode: Int, key: SecretKeySpec, header: ByteArray, index: Int, length: Int) {
        val nonce = header.copyOfRange(24, 32) + ByteBuffer.allocate(4).putInt(index).array()
        cipher.init(mode, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(header + ByteBuffer.allocate(8).putInt(index).putInt(length).array())
    }
    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "备份密码至少需要 8 个字符" }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = magic + salt + prefix + ByteBuffer.allocate(4).putInt(chunkSize).array()
        val secret = key(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val out = DataOutputStream(BufferedOutputStream(output, chunkSize)); out.write(header)
        val buffer = ByteArray(chunkSize); var index = 0
        while (true) {
            check(index < Int.MAX_VALUE) { "备份过大" }
            var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) break
                if (n == 0) continue
                count += n
            }
            // Fresh salt derives a new key per archive; the counter makes each frame nonce unique.
            frame(cipher, Cipher.ENCRYPT_MODE, secret, header, index++, count)
            out.writeInt(count); out.write(cipher.doFinal(buffer, 0, count))
            // Authenticated empty final frame detects truncation at every frame boundary.
            if (count == 0) { out.flush(); return }
        }
    }
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val source = DataInputStream(BufferedInputStream(input, chunkSize))
        val header = ByteArray(36); source.readFully(header)
        if (header.copyOfRange(0, 8).contentEquals(legacyMagic)) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(password, header.copyOfRange(8, 24)), GCMParameterSpec(128, header.copyOfRange(24, 36))); updateAAD(header)
            }
            CipherInputStream(source, cipher).use { it.copyTo(output) }; return
        }
        require(header.copyOfRange(0, 8).contentEquals(magic) && ByteBuffer.wrap(header, 32, 4).int == chunkSize) { "不是受支持的日记备份" }
        val secret = key(password, header.copyOfRange(8, 24))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); var index = 0
        while (true) {
            check(index < Int.MAX_VALUE)
            val length = source.readInt(); require(length in 0..chunkSize) { "备份内容块无效" }
            val encrypted = ByteArray(length + 16); source.readFully(encrypted)
            frame(cipher, Cipher.DECRYPT_MODE, secret, header, index++, length)
            val clear = cipher.doFinal(encrypted)
            // The caller imports only after every frame and the end marker are authenticated.
            if (length == 0) { require(source.read() == -1) { "备份包含多余内容" }; return }
            output.write(clear)
        }
    }
}
