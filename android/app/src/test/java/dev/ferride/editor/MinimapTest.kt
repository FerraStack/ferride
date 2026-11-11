package dev.ferride.editor

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ferride.workbench.FakeWorkspaceBackend
import dev.ferride.workbench.InMemorySettingsStore
import dev.ferride.workbench.WorkbenchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** editor.minimap.enabled defaults (off on phones) and tap-to-scroll on the minimap. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MinimapTest {
    private fun state(settings: String?, compact: Boolean) =
        WorkbenchState(
            FakeWorkspaceBackend(),
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
            settingsStore = InMemorySettingsStore(settings),
        ).also { it.compactWindow = compact }

    @Test
    fun onForTabletsOffForPhonesUnlessSet() {
        assertTrue(state(null, compact = false).minimapEnabled)
        assertFalse(state(null, compact = true).minimapEnabled)
        assertFalse(state("""{ "editor.minimap.enabled": false }""", compact = false).minimapEnabled)
        assertTrue(state("""{ "editor.minimap.enabled": true }""", compact = true).minimapEnabled)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tappingTheMinimapScrollsThere() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CodeEditorView(activity).apply { magnifierEnabled = false }
        view.style = view.style.copy(minimap = true)
        val model = FakeEditorModel((1..2000).joinToString("\n") { "let line$it = $it;" }, "a.rs")
        view.setModel(model)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("starts at the top", true, view.pointOf(0).y > 0f)
        // Tap near the bottom of the minimap (right edge of the view).
        val x = view.width - 20f
        val y = view.height - 10f
        val t = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the editor scrolled down", view.pointOf(0).y < 0f)
        assertEquals("tapping the minimap doesn't move the caret", 0, model.selection.head)
    }
}
