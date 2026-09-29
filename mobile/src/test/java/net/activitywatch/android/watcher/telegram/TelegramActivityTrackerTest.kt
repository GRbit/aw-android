package net.activitywatch.android.watcher.telegram

import net.activitywatch.android.watcher.UiNode
import net.activitywatch.android.watcher.loadDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.threeten.bp.Duration
import org.threeten.bp.Instant

private const val FORKGRAM = "org.forkclient.messenger.beta"
private const val TELEGRAM = "org.telegram.messenger"
private const val KEYBOARD = "com.menny.android.anysoftkeyboard"

// (screen, chat, chat_type, media_type) as they end up in the bucket.
private fun TelegramActivity.row() = listOf(screen.key, chat, chatType, mediaType)

private fun chat(name: String, type: String) = listOf("chat", name, type, "")
private fun media(name: String, type: String, kind: String) = listOf("media", name, type, kind)
private val LIST = listOf("chat_list", "", "", "")
private val UNKNOWN = listOf("unknown", "", "", "")

private fun at(ms: Long): Instant = Instant.ofEpochMilli(1_700_000_000_000 + ms)

private fun UiNode.replaceText(old: String, new: String): UiNode =
    copy(text = if (text == old) new else text, children = children.map { it.replaceText(old, new) })

// Replays an anonymized `uiautomator events` capture (scripts/anonymize-a11y-capture.py)
// through the tracker without any tree snapshots: the event-only mode used when the
// window content of the app cannot be read.
private fun replay(fixture: String, tracker: TelegramActivityTracker): List<CompletedTelegramSession> {
    val stream = TelegramActivityTrackerTest::class.java.classLoader!!.getResourceAsStream("telegram/$fixture")!!
    val sessions = mutableListOf<CompletedTelegramSession>()
    var last = 0L
    stream.bufferedReader().useLines { lines ->
        for (line in lines) {
            if (line.startsWith("#")) continue
            val f = line.split("\t").map { it.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\") }
            val type = when (f[1]) {
                "STATE" -> UiEventType.WINDOW_STATE_CHANGED
                "CLICKED" -> UiEventType.VIEW_CLICKED
                else -> UiEventType.OTHER
            }
            last = f[0].toLong()
            val event = UiEvent(type, f[2].takeIf { it != "null" }, f[3], f[4].ifEmpty { null }, f[5].ifEmpty { null })
            tracker.onEvent(event, at(last))?.let { sessions.add(it) }
        }
    }
    tracker.leave(at(last))?.let { sessions.add(it) }
    return sessions
}

class TelegramActivityTrackerTest {

    private val tracker = TelegramActivityTracker(ignoredPackages = setOf(KEYBOARD))

    private fun snapshot(fixture: String, ms: Long, app: String = FORKGRAM) =
        tracker.onSnapshot(app, loadDump(fixture).root, at(ms))

    private fun current() = tracker.leave(at(1_000_000))!!.activity.row()

    @Test
    fun classifiesOneSnapshotOnAFreshTracker() {
        val cases = mapOf(
            "forkgram-en-channel.xml" to chat("071069b5", "channel"),
            "forkgram-ru-channel.xml" to chat("5fb9ea5f", "channel"),
            "forkgram-en-group.xml" to chat("3abd7dc3", "group"),
            "forkgram-en-direct.xml" to chat("2a4c07a1", "direct"),
            "forkgram-en-list.xml" to LIST,
            "forkgram-ru-list.xml" to LIST,
            // No chat seen before the viewer, so there is nothing to inherit.
            "forkgram-en-video.xml" to media("", "", "video"),
            "forkgram-en-story.xml" to UNKNOWN,
            // Without a viewer to stay in, a bare screen is just unknown.
            "forkgram-en-photo-hidden.xml" to UNKNOWN,
        )
        for ((fixture, expected) in cases) {
            val t = TelegramActivityTracker()
            t.onSnapshot(FORKGRAM, loadDump(fixture).root, at(0))
            assertEquals(fixture, expected, t.leave(at(1))!!.activity.row())
        }
    }

    @Test
    fun commentsKeepTheChannel() {
        snapshot("forkgram-en-channel.xml", 0)
        assertNull(snapshot("forkgram-en-comments.xml", 1_000))
        assertEquals(chat("071069b5", "channel"), current())
    }

    @Test
    fun mediaViewerInheritsTheChatAndSurvivesHiddenControls() {
        snapshot("forkgram-en-channel.xml", 0)
        val done = snapshot("forkgram-en-photo.xml", 5_000)!!
        assertEquals(chat("071069b5", "channel"), done.activity.row())
        assertEquals(Duration.ofSeconds(5), done.duration)
        assertNull(snapshot("forkgram-en-photo-hidden.xml", 6_000))
        assertEquals(media("071069b5", "channel", "image"), snapshot("forkgram-en-video.xml", 7_000)!!.activity.row())
        assertNull(snapshot("forkgram-en-video-hidden.xml", 8_000))
        assertEquals(media("071069b5", "channel", "video"), current())
    }

    @Test
    fun typingIndicatorDoesNotTurnAGroupIntoADirectChat() {
        val group = loadDump("forkgram-en-group.xml").root
        tracker.onSnapshot(FORKGRAM, group, at(0))
        assertNull(tracker.onSnapshot(FORKGRAM, group.replaceText("100 members", "Alex is typing"), at(1)))
        assertEquals(chat("3abd7dc3", "group"), current())
    }

    @Test
    fun listClickTypeWinsOverTheTreeGuess() {
        // A channel admin sees a message field, which the tree alone reads as a group.
        tracker.onEvent(UiEvent(UiEventType.VIEW_CLICKED, FORKGRAM, "android.view.ViewGroup", null,
            "Channel. 3abd7dc3. Muted. Received at 19:39. 1234abcd"), at(0))
        assertNull(snapshot("forkgram-en-group.xml", 500))
        assertEquals(chat("3abd7dc3", "channel"), current())
    }

    @Test
    fun sessionsAreSplitPerApp() {
        snapshot("forkgram-en-list.xml", 0)
        val done = snapshot("forkgram-en-list.xml", 2_000, app = TELEGRAM)!!
        assertEquals(FORKGRAM, done.activity.app)
        assertEquals(TELEGRAM, tracker.leave(at(3_000))!!.activity.app)
        assertNull(tracker.leave(at(4_000)))
    }

    @Test
    fun foreignWindowEndsTheSessionButKeyboardAndSystemUiDoNot() {
        snapshot("forkgram-en-list.xml", 0)
        assertNull(tracker.onEvent(UiEvent(UiEventType.WINDOW_STATE_CHANGED, KEYBOARD, "SoftInputWindow", "Back", null), at(1)))
        assertNull(tracker.onEvent(UiEvent(UiEventType.WINDOW_STATE_CHANGED, "com.android.systemui", "FrameLayout", null, null), at(2)))
        assertNull(tracker.onEvent(UiEvent(UiEventType.WINDOW_CONTENT_CHANGED, "fr.neamar.kiss", "FrameLayout", null, null), at(3)))
        val done = tracker.onEvent(UiEvent(UiEventType.WINDOW_STATE_CHANGED, "fr.neamar.kiss", "MainActivity", "KISS launcher", null), at(4_000))!!
        assertEquals(LIST, done.activity.row())
        assertEquals(Duration.ofSeconds(4), done.duration)
        assertNull(tracker.leave(at(5_000)))
    }

    @Test
    fun replaysEventsWithoutTree() {
        val cases = mapOf(
            "forkgram-en-events.tsv" to listOf(
                chat("9df869ff", "direct"),
                // A permission dialog over the list splits it into two sessions.
                LIST,
                LIST,
                chat("6fb63141", "channel"),
                chat("3b406ea6", "channel"),
                media("3b406ea6", "channel", "video"),
                // Back in the chat three times after switching to other apps.
                chat("3b406ea6", "channel"),
                chat("3b406ea6", "channel"),
                chat("3b406ea6", "channel"),
            ),
            "forkgram-ru-events.tsv" to listOf(
                LIST,
                chat("9df869ff", "direct"),
                chat("6fb63141", "channel"),
                chat("3b406ea6", "channel"),
                media("3b406ea6", "channel", "video"),
                chat("3b406ea6", "channel"),
            ),
            "telegram-en-events.tsv" to listOf(
                LIST,
                chat("b17b4860", "direct"),
                chat("318fb1b7", "group"),
                chat("492a7632", "channel"),
                media("492a7632", "channel", "image"),
                chat("492a7632", "channel"),
            ),
        )
        for ((fixture, expected) in cases) {
            val t = TelegramActivityTracker(ignoredPackages = setOf(KEYBOARD))
            assertEquals(fixture, expected, replay(fixture, t).map { it.activity.row() })
        }
    }
}
