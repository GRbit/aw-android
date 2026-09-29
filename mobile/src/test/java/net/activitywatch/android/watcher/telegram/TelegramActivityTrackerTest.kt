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

private fun at(ms: Long): Instant = Instant.ofEpochMilli(1_700_000_000_000 + ms)

private fun UiNode.replaceText(old: String, new: String): UiNode =
    copy(text = if (text == old) new else text, children = children.map { it.replaceText(old, new) })

private fun UiNode.withoutEditText(): UiNode =
    copy(children = children.filter { it.className != "android.widget.EditText" }.map { it.withoutEditText() })

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
                "CONTENT" -> UiEventType.WINDOW_CONTENT_CHANGED
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
    fun classifiesChatHeaders() {
        val cases = mapOf(
            "forkgram-en-channel.xml" to listOf("chat", "071069b5", "channel", ""),
            "forkgram-ru-channel.xml" to listOf("chat", "5fb9ea5f", "channel", ""),
            "forkgram-en-group.xml" to listOf("chat", "3abd7dc3", "group", ""),
            "forkgram-ru-group.xml" to listOf("chat", "83f9e063", "group", ""),
            "forkgram-en-direct.xml" to listOf("chat", "2a4c07a1", "direct", ""),
            "forkgram-ru-direct.xml" to listOf("chat", "5507c090", "direct", ""),
            "forkgram-en-list.xml" to listOf("chat_list", "", "", ""),
            "forkgram-ru-list.xml" to listOf("chat_list", "", "", ""),
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
        assertEquals(listOf("chat", "071069b5", "channel", ""), current())

        val ru = TelegramActivityTracker()
        ru.onSnapshot(FORKGRAM, loadDump("forkgram-ru-channel.xml").root, at(0))
        assertNull(ru.onSnapshot(FORKGRAM, loadDump("forkgram-ru-comments.xml").root, at(1)))
    }

    @Test
    fun mediaViewerInheritsTheChat() {
        snapshot("forkgram-en-channel.xml", 0)
        val chat = snapshot("forkgram-en-photo.xml", 5_000)!!
        assertEquals(listOf("chat", "071069b5", "channel", ""), chat.activity.row())
        assertEquals(Duration.ofSeconds(5), chat.duration)
        assertNull(snapshot("forkgram-en-photo-hidden.xml", 6_000))
        assertEquals(listOf("media", "071069b5", "channel", "image"), current())
    }

    @Test
    fun videoViewerWithHiddenControlsStaysVideo() {
        snapshot("forkgram-ru-channel.xml", 0)
        snapshot("forkgram-ru-video.xml", 1_000)
        assertNull(snapshot("forkgram-en-video-hidden.xml", 2_000))
        assertEquals(listOf("media", "5fb9ea5f", "channel", "video"), current())
    }

    @Test
    fun viewerWithoutKnownChat() {
        snapshot("forkgram-en-video.xml", 0)
        assertEquals(listOf("media", "", "", "video"), current())
    }

    @Test
    fun unrecognizedScreenIsUnknown() {
        snapshot("forkgram-en-list.xml", 0)
        snapshot("forkgram-en-story.xml", 1_000)
        assertEquals(listOf("unknown", "", "", ""), current())
        snapshot("forkgram-en-photo-hidden.xml", 0)
        assertEquals(listOf("unknown", "", "", ""), current())
    }

    @Test
    fun typingIndicatorDoesNotTurnAGroupIntoADirectChat() {
        val group = loadDump("forkgram-en-group.xml").root
        tracker.onSnapshot(FORKGRAM, group, at(0))
        assertNull(tracker.onSnapshot(FORKGRAM, group.replaceText("100 members", "Alex is typing"), at(1)))
        assertEquals(listOf("chat", "3abd7dc3", "group", ""), current())
    }

    @Test
    fun listClickTypeWinsOverTheTreeGuess() {
        // A channel admin sees a message field, which the tree alone reads as a group.
        tracker.onEvent(UiEvent(UiEventType.VIEW_CLICKED, FORKGRAM, "android.view.ViewGroup", null,
            "Channel. 3abd7dc3. Muted. Received at 19:39. 1234abcd"), at(0))
        assertNull(snapshot("forkgram-en-group.xml", 500))
        assertEquals(listOf("chat", "3abd7dc3", "channel", ""), current())
    }

    @Test
    fun channelWithoutMessageFieldIsChannel() {
        tracker.onSnapshot(FORKGRAM, loadDump("forkgram-en-group.xml").root.withoutEditText(), at(0))
        assertEquals(listOf("chat", "3abd7dc3", "channel", ""), current())
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
        assertEquals(listOf("chat_list", "", "", ""), done.activity.row())
        assertEquals(Duration.ofSeconds(4), done.duration)
        assertNull(tracker.leave(at(5_000)))
    }

    @Test
    fun replaysForkgramEnglishEventsWithoutTree() {
        val rows = replay("forkgram-en-events.tsv", tracker).map { it.activity.row() }
        assertEquals(
            listOf(
                listOf("chat", "9df869ff", "direct", ""),
                listOf("chat_list", "", "", ""),
                listOf("chat_list", "", "", ""),
                listOf("chat", "6fb63141", "channel", ""),
                listOf("chat", "3b406ea6", "channel", ""),
                listOf("media", "3b406ea6", "channel", "video"),
                listOf("chat", "3b406ea6", "channel", ""),
                listOf("chat", "3b406ea6", "channel", ""),
                listOf("chat", "3b406ea6", "channel", ""),
            ),
            rows.take(9),
        )
    }

    @Test
    fun replaysForkgramRussianEventsWithoutTree() {
        val rows = replay("forkgram-ru-events.tsv", tracker).map { it.activity.row() }
        assertEquals(
            listOf(
                listOf("chat_list", "", "", ""),
                listOf("chat", "9df869ff", "direct", ""),
                listOf("chat", "6fb63141", "channel", ""),
                listOf("chat", "3b406ea6", "channel", ""),
                listOf("media", "3b406ea6", "channel", "video"),
                listOf("chat", "3b406ea6", "channel", ""),
            ),
            rows,
        )
    }

    @Test
    fun replaysTelegramEventsWithoutTree() {
        val rows = replay("telegram-en-events.tsv", tracker).map { it.activity.row() }
        assertEquals(
            listOf(
                listOf("chat_list", "", "", ""),
                listOf("chat", "b17b4860", "direct", ""),
                listOf("chat", "318fb1b7", "group", ""),
                listOf("chat", "492a7632", "channel", ""),
                listOf("media", "492a7632", "channel", "image"),
                listOf("chat", "492a7632", "channel", ""),
            ),
            rows,
        )
    }
}
