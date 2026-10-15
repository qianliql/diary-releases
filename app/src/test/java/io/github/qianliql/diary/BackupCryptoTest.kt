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
}
