package io.github.qianliql.diary

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qianliql.diary.backup.BackupCrypto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class LargeBackupTest {
    @Test fun largeArchiveStreamsWithBoundedWorkingSet() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ActivityScenario.launch(MainActivity::class.java).use {
            val file = File(context.cacheDir,"large-backup-test.bin")
            val size = 160L * 1024 * 1024; var produced = 0L; var restored = 0L
            val expected = MessageDigest.getInstance("SHA-256"); val actual = MessageDigest.getInstance("SHA-256")
            val started = System.nanoTime()
            try {
                val source = object: InputStream() {
                    override fun read(): Int = if (produced == size) -1 else { produced++; expected.update(0x5a.toByte()); 0x5a }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (produced == size) return -1
                        if (produced > 1024*1024) assertTrue("Encryption buffered the whole archive", file.length() > produced - 256*1024)
                        val count = minOf(length.toLong(), size - produced).toInt()
                        java.util.Arrays.fill(bytes,offset,offset+count,0x5a.toByte()); expected.update(bytes,offset,count); produced += count
                        return count
                    }
                }
                file.outputStream().use { out -> BackupCrypto.encrypt(source,out,"large-backup-password".toCharArray()) }
                val sink = object: OutputStream() {
                    override fun write(value: Int) { actual.update(value.toByte()); restored++ }
                    override fun write(bytes: ByteArray,offset: Int,length: Int) { actual.update(bytes,offset,length); restored += length }
                }
                file.inputStream().use { input -> BackupCrypto.decrypt(input,sink,"large-backup-password".toCharArray()) }
                assertEquals(size,restored); assertArrayEquals(expected.digest(),actual.digest())
                File(context.filesDir,"qa-large-backup.txt").writeText("160 MiB encrypted and restored in ${(System.nanoTime()-started)/1e9} seconds; hashes match; output progressed within 256 KiB of input. Java heap limit ${Runtime.getRuntime().maxMemory()} bytes.\n")
            } finally { file.delete() }
        }
    }
}
