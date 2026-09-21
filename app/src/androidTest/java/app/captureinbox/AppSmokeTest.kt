package app.captureinbox

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model: InboxViewModel get() = ViewModelProvider(compose.activity)[InboxViewModel::class.java]

    private fun record(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Before fun cleanTestLibrary() {
        compose.waitUntil(20_000) { !model.busy }
        runBlocking { model.repository.readAll().forEach { model.repository.delete(it) } }
        compose.runOnIdle { model.reload() }
        compose.waitUntil(20_000) { !model.busy && model.items.isEmpty() }
    }

    @After fun recordFinalState() {
        record("09-ui-final")
        runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { i ->
            compose.onAllNodes(isRoot(), useUnmergedTree = true)[i].printToLog("CaptureUiTree")
        } }
    }

    @Test fun launchTouchEditAndReadSavedLibrary() {
        compose.onNodeWithText("첫 스크린샷 가져오기").assertIsDisplayed()
        record("01-empty")
        compose.onNodeWithText("예시로 둘러보기").performClick()
        compose.waitUntil(20_000) { !model.busy && model.items.size >= 5 }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        record("02-library")
        compose.onNode(hasText("쿠폰") and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))).performClick()
        compose.waitForIdle()
        val coupon = compose.runOnIdle { model.items.first { it.title == "모닝브루 아메리카노" } }
        compose.onNode(hasText(coupon.title) and hasClickAction()).assertIsDisplayed()
            .performTouchInput { click(Offset(center.x, 32f)) }
        compose.runOnIdle { assertEquals("Card touch must select the coupon", coupon.id, model.selectedId) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasSetTextAction() and hasText(coupon.title)).fetchSemanticsNodes().isNotEmpty() }
        record("03-editor")
        compose.onNode(hasSetTextAction() and hasText(coupon.title)).performTextReplacement("테스트로 수정한 쿠폰")
        compose.onNodeWithText("저장", useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { !model.busy && model.selectedId == null && model.items.any { it.title == "테스트로 수정한 쿠폰" } }
        assertTrue(runBlocking { CaptureRepository(compose.activity.applicationContext).readAll().any { it.title == "테스트로 수정한 쿠폰" } })
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("테스트로 수정한 쿠폰").assertIsDisplayed()
        record("04-edited-library")
    }
}
