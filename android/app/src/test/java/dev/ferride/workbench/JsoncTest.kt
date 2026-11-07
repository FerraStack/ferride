package dev.ferride.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsoncTest {
    @Test
    fun stripKeepsStringContents() {
        val src = """{ "a": "x,}", /* c */ "b": [1, 2,], // tail
  "c": "//not a comment", }"""
        val out = Jsonc.strip(src)
        assertTrue(out, out.contains("\"x,}\""))
        assertTrue(out, out.contains("\"//not a comment\""))
        assertTrue(out, !out.contains("/* c */"))
        // Trailing commas before ] and } are gone (the ",}" inside the string stays).
        assertTrue(out, out.contains("2 ]"))
        assertTrue(out, out.contains("\"//not a comment\" }"))
    }

    @Test
    fun setPropertyReplacesInsertsAndRemoves() {
        val src = "{\n  // keep\n  \"a\": 1,\n  \"b\": { \"x\": [1, 2] } // obj\n}\n"
        val replaced = Jsonc.setProperty(src, "a", "2")
        assertEquals("{\n  // keep\n  \"a\": 2,\n  \"b\": { \"x\": [1, 2] } // obj\n}\n", replaced)
        val objReplaced = Jsonc.setProperty(src, "b", "true")
        assertTrue(objReplaced, objReplaced.contains("\"b\": true // obj"))
        val inserted = Jsonc.setProperty(src, "c", Jsonc.quote("on"))
        assertTrue(inserted, inserted.contains("\"b\": { \"x\": [1, 2] },\n  \"c\": \"on\""))
        val removed = Jsonc.setProperty(src, "a", null)
        assertTrue(removed, !removed.contains("\"a\""))
        assertTrue(removed, removed.contains("// keep"))
        val removedLast = Jsonc.setProperty(src, "b", null)
        assertTrue(removedLast, !removedLast.contains("\"b\"") && removedLast.contains("\"a\": 1"))
        assertEquals("{\n  \"k\": 1\n}", Jsonc.setProperty("{}", "k", "1"))
        assertEquals("{\n  \"k\": 1\n}", Jsonc.setProperty("", "k", "1"))
    }

    @Test
    fun trailingCommaInsert() {
        val src = "{ \"a\": 1, }"
        val out = Jsonc.setProperty(src, "b", "2")
        assertEquals("{ \"a\": 1,\n  \"b\": 2 }", out)
    }

    @Test(expected = Jsonc.ParseException::class)
    fun unterminatedStringFails() {
        Jsonc.tokenize("{ \"a")
    }
}
