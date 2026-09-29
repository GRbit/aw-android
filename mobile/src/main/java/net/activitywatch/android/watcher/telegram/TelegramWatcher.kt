package net.activitywatch.android.watcher.telegram

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import net.activitywatch.android.watcher.AccessibilityNodeSnapshotOps
import net.activitywatch.android.watcher.buildSnapshot
import org.threeten.bp.Instant

internal const val TELEGRAM_BUCKET_ID = "aw-watcher-android-telegram"
internal const val TELEGRAM_BUCKET_TYPE = "app.chat.current"

private const val TAG = "TelegramWatcher"

// Service-side glue for TelegramActivityTracker: maps accessibility events, reads the
// window tree when SnapshotScheduler asks for it and hands finished sessions to `emit`.
// Runs on the accessibility service's main thread.
internal class TelegramWatcher(
    private val service: AccessibilityService,
    private val emit: (CompletedTelegramSession) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val scheduler = SnapshotScheduler()
    private val tracker = TelegramActivityTracker(ignoredPackages = inputMethodPackages(service))
    private var foregroundPackage: String? = null
    private val treeReadable = HashMap<String, Boolean>()
    private var logged: TelegramActivity? = null
    private val snapshotTask = Runnable { takeSnapshot() }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Telegram left open while the screen is off is not time spent in it.
            cancelSnapshot()
            foregroundPackage = null
            finish(tracker.leave(now()))
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            service, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun stop() {
        cancelSnapshot()
        try {
            service.unregisterReceiver(screenOffReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Screen-off receiver was not registered")
        }
        finish(tracker.leave(now()))
    }

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val isTelegram = pkg in TELEGRAM_PACKAGES
        // Hot path: every event of every app passes here.
        if (!isTelegram && foregroundPackage == null && tracker.current == null) return

        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> UiEventType.WINDOW_STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> UiEventType.WINDOW_CONTENT_CHANGED
            AccessibilityEvent.TYPE_VIEW_CLICKED -> UiEventType.VIEW_CLICKED
            else -> UiEventType.OTHER
        }
        val uiEvent = UiEvent(
            type = type,
            packageName = pkg,
            className = event.className?.toString(),
            text = if (type == UiEventType.WINDOW_STATE_CHANGED) event.text.joinToString(", ") else null,
            contentDescription = if (type == UiEventType.VIEW_CLICKED) event.contentDescription?.toString() else null,
        )
        val now = now()
        finish(tracker.onEvent(uiEvent, now))

        if (isTelegram) {
            foregroundPackage = pkg
            scheduler.onEvent(type, now)?.let { due ->
                handler.removeCallbacks(snapshotTask)
                handler.postDelayed(snapshotTask, (due.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0))
            }
        } else if (tracker.current == null) {
            foregroundPackage = null
            cancelSnapshot()
        }
    }

    private fun cancelSnapshot() {
        handler.removeCallbacks(snapshotTask)
        scheduler.onSnapshotTaken()
    }

    private fun takeSnapshot() {
        scheduler.onSnapshotTaken()
        val pkg = foregroundPackage ?: return
        val root = findRoot(pkg)
        logMode(pkg, readable = root != null)
        if (root == null) return
        val started = SystemClock.elapsedRealtime()
        val snapshot = try {
            buildSnapshot(root, AccessibilityNodeSnapshotOps)
        } finally {
            root.recycle()
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Snapshot of $pkg: ${snapshot.nodeCount} nodes in ${SystemClock.elapsedRealtime() - started} ms" +
                if (snapshot.truncated) " (truncated)" else "")
        }
        finish(tracker.onSnapshot(pkg, snapshot.root, now()))
    }

    // The media viewer is its own window and a work-profile app may not be the active
    // window, so prefer the top-most application window of the package.
    private fun findRoot(pkg: String): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestLayer = Int.MIN_VALUE
        for (window in service.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.layer <= bestLayer) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() == pkg) {
                best?.recycle()
                best = root
                bestLayer = window.layer
            } else {
                root.recycle()
            }
        }
        if (best != null) return best
        val active = service.rootInActiveWindow ?: return null
        if (active.packageName?.toString() == pkg) return active
        active.recycle()
        return null
    }

    private fun logMode(pkg: String, readable: Boolean) {
        if (treeReadable.put(pkg, readable) == readable) return
        if (readable) Log.i(TAG, "$pkg: reading window tree")
        else Log.i(TAG, "$pkg: window tree not readable, using events only")
    }

    private fun finish(session: CompletedTelegramSession?) {
        session?.let(emit)
        val next = tracker.current
        if (next != logged) {
            logged = next
            if (next == null) Log.i(TAG, "Left Telegram")
            else Log.i(TAG, "Now: ${next.app} ${next.screen.key} chat=\"${next.chat}\" type=${next.chatType} media=${next.mediaType}")
        }
    }

    private fun now(): Instant = Instant.ofEpochMilli(System.currentTimeMillis())
}

private fun inputMethodPackages(context: Context): Set<String> = try {
    val imm = context.getSystemService(InputMethodManager::class.java)
    imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
} catch (e: RuntimeException) {
    Log.w(TAG, "Could not list input methods; keyboard windows will end Telegram sessions", e)
    emptySet()
}
