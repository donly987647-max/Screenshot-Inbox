package app.captureinbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {
    @Test fun koreanOcrDedupAndPortableBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = CaptureRepository(context)
        runBlocking { repo.readAll().forEach { repo.delete(it) } }
        val fixture = File(context.cacheDir, "ocr-fixture.png")
        val bitmap = Bitmap.createBitmap(1100, 420, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
            drawText("커피 쿠폰 COFFEE COUPON", 40f, 100f, paint)
            drawText("유효기간: 2026.09.30", 40f, 210f, paint)
            drawText("테스트 이미지 / TEST ONLY", 40f, 320f, paint)
        }
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        runBlocking {
            val result = repo.import(Uri.fromFile(fixture))
            assertFalse(result.duplicate)
            assertEquals(Category.COUPON, result.capture.category)
            assertEquals("2026-09-30", result.capture.date)
            assertFalse(result.capture.dateConfirmed)
            assertNull(result.capture.remindDays)
            assertTrue(repo.imageFile(result.capture)!!.isFile)
            val duplicate = repo.import(Uri.fromFile(fixture))
            assertTrue(duplicate.duplicate)
            assertEquals(result.capture.id, duplicate.capture.id)
            val count = repo.readAll().size
            val hostile = File(context.cacheDir, "hostile.zip")
            ZipOutputStream(hostile.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("../../should-not-exist.txt"))
                zip.write("bad".toByteArray()); zip.closeEntry()
            }
            assertTrue(runCatching { repo.restoreFrom(Uri.fromFile(hostile)) }.isFailure)
            assertEquals(count, repo.readAll().size)
            val backup = File(context.cacheDir, "backup.zip")
            repo.exportTo(Uri.fromFile(backup))
            assertTrue(backup.length() > 0)
            assertEquals(0, repo.restoreFrom(Uri.fromFile(backup)))
            val originalHash = result.capture.digest
            repo.readAll().forEach { repo.delete(it) }
            assertTrue(repo.readAll().isEmpty())
            assertEquals(count, repo.restoreFrom(Uri.fromFile(backup)))
            assertEquals(count, repo.readAll().size)
            val restored = repo.readAll().first { it.digest == originalHash }
            assertNotEquals(result.capture.id, restored.id)
            assertNull(restored.remindDays)
            assertArrayEquals(fixture.readBytes(), repo.imageFile(restored)!!.readBytes())
        }
    }
}
