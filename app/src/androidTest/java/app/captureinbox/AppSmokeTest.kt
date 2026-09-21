package app.captureinbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchEditPersistRecognizeAndRestore() {
        compose.onNodeWithText("첫 스크린샷 가져오기").assertIsDisplayed()
        compose.onNodeWithText("예시로 둘러보기").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("모닝브루 아메리카노").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("모닝브루 아메리카노").assertIsDisplayed()
        val screenshots = File(compose.activity.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(screenshots, "01-library.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("모닝브루 아메리카노").performClick()
        compose.onNode(hasSetTextAction() and hasText("모닝브루 아메리카노")).performTextReplacement("테스트로 수정한 쿠폰")
        compose.onNodeWithText("저장", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("테스트로 수정한 쿠폰").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("테스트로 수정한 쿠폰").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("테스트로 수정한 쿠폰").assertIsDisplayed()
        val context = compose.activity.applicationContext
        val repo = CaptureRepository(context)
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
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("테스트로 수정한 쿠폰").fetchSemanticsNodes().isNotEmpty() }
        File(screenshots, "02-restored-library.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
