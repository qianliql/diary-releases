package io.github.qianliql.diary
import io.github.qianliql.diary.backup.BackupCrypto
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackupCryptoTest {
    private val password = "日记测试密码123".toCharArray()
    @Test fun roundTripAndFreshSalt() {
        val input = "文字、照片目录和语音转写".repeat(1000).toByteArray()
        fun encrypted() = ByteArrayOutputStream().also { BackupCrypto.encrypt(input.inputStream(), it, password) }.toByteArray()
        val first = encrypted(); val second = encrypted(); assertFalse(first.contentEquals(second))
        val restored = ByteArrayOutputStream(); BackupCrypto.decrypt(first.inputStream(), restored, password)
        assertArrayEquals(input, restored.toByteArray()); assertFalse(String(first).contains("语音转写"))
    }
    @Test fun wrongPasswordRejected() {
        val out = ByteArrayOutputStream(); BackupCrypto.encrypt("private".byteInputStream(), out, password)
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(out.toByteArray().inputStream(), ByteArrayOutputStream(), "incorrect".toCharArray()) }
    }
    @Test fun tamperingRejected() {
        val out = ByteArrayOutputStream(); BackupCrypto.encrypt(ByteArray(1024).inputStream(), out, password)
        val data = out.toByteArray(); data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { BackupCrypto.decrypt(data.inputStream(), ByteArrayOutputStream(), password) }
    }
    @Test fun rejectsShortPassword() { assertThrows(IllegalArgumentException::class.java) { BackupCrypto.encrypt("x".byteInputStream(), ByteArrayOutputStream(), "short".toCharArray()) } }
    @Test fun framesRejectTruncationReorderingAndTrailingBytes() {
        val output = ByteArrayOutputStream(); BackupCrypto.encrypt(ByteArray(65536 * 2 + 17).inputStream(), output, password)
        val bytes = output.toByteArray(); val frameSize = 65536 + 20
        val reordered = bytes.copyOfRange(0, 36) + bytes.copyOfRange(36 + frameSize, 36 + frameSize * 2) + bytes.copyOfRange(36, 36 + frameSize) + bytes.copyOfRange(36 + frameSize * 2, bytes.size)
        for (invalid in listOf(bytes.copyOf(bytes.size - 20), reordered, bytes + byteArrayOf(1))) {
            assertThrows(Exception::class.java) { BackupCrypto.decrypt(invalid.inputStream(), ByteArrayOutputStream(), password) }
        }
    }
    @Test fun restoresVersionOneBackups() {
        val salt = ByteArray(16) { it.toByte() }; val iv = ByteArray(12) { (it + 20).toByte() }
        val header = "DIARYBK1".toByteArray() + salt + iv
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, 600000, 256)
        val key = javax.crypto.spec.SecretKeySpec(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv)); cipher.updateAAD(header)
        val original = "0.0.0 备份可恢复".toByteArray(); val output = ByteArrayOutputStream()
        BackupCrypto.decrypt((header + cipher.doFinal(original)).inputStream(), output, password)
        assertArrayEquals(original, output.toByteArray())
    }
}
