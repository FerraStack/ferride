package dev.ferride.workbench

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** #9: a newer query cancels the running search and stale results never overwrite newer ones. */
@RunWith(AndroidJUnit4::class)
class SearchCancelTest {
    @Test
    fun slowOldSearchIsCancelledAndItsResultsDropped() {
        val release = CountDownLatch(1)
        val tokens = mutableMapOf<String, SearchToken>()
        val backend = FakeWorkspaceBackend(onSearch = { q, token ->
            synchronized(tokens) { tokens[q] = token }
            if (q == "fn") release.await(5, TimeUnit.SECONDS) // the first query is slow
        })
        val pool = Executors.newFixedThreadPool(2)
        val io = pool.asCoroutineDispatcher()
        val s = WorkbenchState(backend, CoroutineScope(Dispatchers.Unconfined), io)

        s.searchQuery = "fn"
        s.runSearch()
        waitUntil { synchronized(tokens) { "fn" in tokens } }
        s.searchQuery = "word"
        s.runSearch()
        waitUntil { s.searchResult != null }
        assertTrue("the older search was cancelled", synchronized(tokens) { tokens["fn"]!!.cancelled })
        val newer = s.searchResult!!
        assertTrue(newer.hits.all { "word" in it.lineText.lowercase() })

        // The old search finishes last; its (cancelled) result must not replace the newer one.
        release.countDown()
        Thread.sleep(200)
        assertEquals(newer, s.searchResult)
        assertFalse(s.searching)
        pool.shutdown()
    }

    @Test
    fun clearingTheQueryCancelsAndClears() {
        val tokens = mutableListOf<SearchToken>()
        val s = WorkbenchState(
            FakeWorkspaceBackend(onSearch = { _, t -> tokens += t }),
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
        )
        s.searchQuery = "fn"
        s.runSearch()
        assertTrue(s.searchResult!!.hits.isNotEmpty())
        s.searchQuery = ""
        s.runSearch()
        assertEquals(null, s.searchResult)
        assertTrue(tokens.single().cancelled)
    }

    @Test
    fun tokenListenerNeverRunsAfterClear() {
        val t = SearchToken()
        var calls = 0
        t.onCancel { calls++ }
        t.clearListener()
        t.cancel()
        assertEquals(0, calls)
        val late = SearchToken().apply { cancel() }
        late.onCancel { calls++ }
        assertEquals("a listener added after cancel runs at once", 1, calls)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }
}
