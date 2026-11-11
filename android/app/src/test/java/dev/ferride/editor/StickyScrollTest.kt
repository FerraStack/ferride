package dev.ferride.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickyScrollTest {
    private val h = 10f
    private fun at(sticky: StickyScroll, topLine: Int) = sticky.update(topLine * h, h) { it }

    @Test
    fun enclosingScopesStickOutermostFirst() {
        // namespace 5..25 > class 9..23 > method 13..19, and a later function 27..34.
        val sticky = StickyScroll().apply { scopes = listOf(5..25, 9..23, 13..19, 27..34) }
        assertEquals(emptyList<Int>(), at(sticky, 0))
        assertEquals(listOf(5), at(sticky, 7))
        // The sticky stack covers lines too: at line 12 the first line below
        // two sticky rows is 14, inside the method.
        assertEquals(listOf(5, 9, 13), at(sticky, 12))
        // Past the method's end only the outer two stick.
        assertEquals(listOf(5, 9), at(sticky, 20))
        assertEquals(listOf(27), at(sticky, 29))
        assertEquals(emptyList<Int>(), at(sticky, 40))
    }

    @Test
    fun capAndSwitchOff() {
        val nested = (0 until 8).map { it..(50 - it) }
        val sticky = StickyScroll().apply { scopes = nested }
        assertEquals(StickyScroll.MAX_LINES, at(sticky, 20).size)
        sticky.enabled = false
        assertEquals(emptyList<Int>(), at(sticky, 20))
    }
}
