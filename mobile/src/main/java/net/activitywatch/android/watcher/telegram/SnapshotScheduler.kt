package net.activitywatch.android.watcher.telegram

import org.threeten.bp.Duration
import org.threeten.bp.Instant

// A click opens the next screen with an animation; a snapshot taken at the click would
// still show the old screen.
internal const val CLICK_SETTLE_MS = 700L

// Scrolling fires ~9 content changes per second; reading the tree for each would cost
// battery for no new information, so wait for a pause...
internal const val CONTENT_DEBOUNCE_MS = 2_000L

// ...but not forever: a playing video or a busy group never pauses.
internal const val CONTENT_MAX_DELAY_MS = 10_000L

// Decides when the service should read the window tree. Pure so the timing is testable;
// the service owns the actual timer and calls onSnapshotTaken() when it reads the tree.
internal class SnapshotScheduler {
    private var pendingSince: Instant? = null

    // Returns when to take the next snapshot (replacing any previously returned time), or
    // null when this event does not need one.
    fun onEvent(type: UiEventType, now: Instant): Instant? = when (type) {
        UiEventType.WINDOW_STATE_CHANGED -> now
        UiEventType.VIEW_CLICKED -> now.plusMillis(CLICK_SETTLE_MS)
        UiEventType.WINDOW_CONTENT_CHANGED -> {
            val since = pendingSince ?: now.also { pendingSince = it }
            minOf(now.plusMillis(CONTENT_DEBOUNCE_MS), since.plus(Duration.ofMillis(CONTENT_MAX_DELAY_MS)))
        }
        UiEventType.OTHER -> null
    }

    fun onSnapshotTaken() {
        pendingSince = null
    }
}
