package dev.ferride.editor

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ferride.core.ViewLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** #14: CodeEditorView invalidates cached highlights and notifies host when background parsing finishes. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundSyntaxTest {
    private class AsyncSyntaxModel(private val inner: FakeEditorModel) : EditorModel by inner {
        var viewportCalls = 0
        override var syntaxVersion: Long = 0L
        override var onSyntaxUpdated: (() -> Unit)? = null

        override fun viewport(firstLine: Int, count: Int): List<ViewLine> {
            viewportCalls++
            return inner.viewport(firstLine, count)
        }
    }

    @Test
    fun backgroundSyntaxUpdateInvalidatesLineCacheAndNotifiesHost() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CodeEditorView(activity).apply { magnifierEnabled = false }
        val model = AsyncSyntaxModel(FakeEditorModel("fn main() {}\n", "main.rs"))
        var hostNotified = 0
        view.onSyntaxUpdated = { hostNotified++ }
        view.setModel(model)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()

        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)
        val callsAfterFirstDraw = model.viewportCalls
        view.draw(canvas)
        assertEquals("cached viewport reused when syntaxVersion unchanged", callsAfterFirstDraw, model.viewportCalls)

        // Simulate background parse completion without changing buffer version.
        model.syntaxVersion = 1L
        model.onSyntaxUpdated!!.invoke()
        assertEquals(1, hostNotified)

        view.draw(canvas)
        assertEquals("viewport re-queried after syntaxVersion bump", callsAfterFirstDraw + 1, model.viewportCalls)

        // Detaching clears the model listener.
        view.setModel(null)
        assertNull(model.onSyntaxUpdated)
    }
}
