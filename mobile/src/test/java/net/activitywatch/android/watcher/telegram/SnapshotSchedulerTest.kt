package net.activitywatch.android.watcher.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.threeten.bp.Instant

class SnapshotSchedulerTest {

    private val scheduler = SnapshotScheduler()

    private fun at(ms: Long): Instant = Instant.ofEpochMilli(ms)

    @Test
    fun windowStateChangeIsSnapshottedImmediately() {
        assertEquals(at(1_000), scheduler.onEvent(UiEventType.WINDOW_STATE_CHANGED, at(1_000)))
    }

    @Test
    fun clickWaitsForTheNextScreenToRender() {
        assertEquals(at(1_000 + CLICK_SETTLE_MS), scheduler.onEvent(UiEventType.VIEW_CLICKED, at(1_000)))
    }

    @Test
    fun contentChangesAreDebounced() {
        assertEquals(at(CONTENT_DEBOUNCE_MS), scheduler.onEvent(UiEventType.WINDOW_CONTENT_CHANGED, at(0)))
        assertEquals(at(500 + CONTENT_DEBOUNCE_MS), scheduler.onEvent(UiEventType.WINDOW_CONTENT_CHANGED, at(500)))
    }

    @Test
    fun steadyContentChangesStillSnapshotEveryMaxDelay() {
        var due: Instant? = null
        for (ms in 0L..30_000L step 100) {
            val now = at(ms)
            if (due != null && !now.isBefore(due)) {
                assertEquals(at((ms / CONTENT_MAX_DELAY_MS) * CONTENT_MAX_DELAY_MS), due)
                scheduler.onSnapshotTaken()
            }
            due = scheduler.onEvent(UiEventType.WINDOW_CONTENT_CHANGED, now)
        }
    }

    @Test
    fun otherEventsDoNotSchedule() {
        assertNull(scheduler.onEvent(UiEventType.OTHER, at(0)))
    }
}
