package net.activitywatch.android.watcher.telegram

import net.activitywatch.android.watcher.UiNode
import org.threeten.bp.Duration
import org.threeten.bp.Instant

// Official Telegram and Forkgram (same UI code, Forkgram tracks upstream). The
// ".web" and non-beta Forkgram ids are the other published flavors.
internal val TELEGRAM_PACKAGES = setOf(
    "org.telegram.messenger",
    "org.telegram.messenger.web",
    "org.forkclient.messenger",
    "org.forkclient.messenger.beta",
)

// Window title of LaunchActivity on the chat list: the app label. Inside a chat the title
// is the chat name instead.
internal val TELEGRAM_LIST_TITLES = setOf("Telegram", "Fork Client")

private const val SYSTEM_UI = "com.android.systemui"
private const val MAX_REMEMBERED_CHATS = 500

// Chat list row descriptions start with a localized type; direct chats have no prefix.
private val ROW_PREFIXES = listOf(
    "Channel. " to ChatType.CHANNEL,
    "Group. " to ChatType.GROUP,
    "Канал. " to ChatType.CHANNEL,
    "Группа. " to ChatType.GROUP,
)

// The media viewer window announces its toolbar. Forkgram keeps these strings English in
// every locale; other translations fall back to the tree snapshot.
private const val VIEWER_TITLE_PREFIX = "Go back"
private const val VIEWER_VIDEO_MARKER = "Switch to fullscreen"

private const val IMAGE_VIEW = "android.widget.ImageView"
private const val FRAME_LAYOUT = "android.widget.FrameLayout"
private const val TEXT_VIEW = "android.widget.TextView"
private const val EDIT_TEXT = "android.widget.EditText"
private val VIDEO_CLASSES = setOf("android.view.SurfaceView", "android.view.TextureView", "android.widget.SeekBar")
private const val LIST_LOGO_DESCRIPTION = "Telegram"

internal enum class UiEventType { WINDOW_STATE_CHANGED, WINDOW_CONTENT_CHANGED, VIEW_CLICKED, OTHER }

// The parts of an AccessibilityEvent the tracker reads. `text` is only needed for window
// state changes and must not be filled for other types: content changes carry messages.
internal data class UiEvent(
    val type: UiEventType,
    val packageName: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
)

internal enum class TelegramScreen(val key: String) {
    CHAT("chat"),
    MEDIA("media"),
    CHAT_LIST("chat_list"),
    UNKNOWN("unknown"),
}

private enum class ChatType(val key: String) {
    CHANNEL("channel"),
    GROUP("group"),
    DIRECT("direct"),
    UNKNOWN("unknown"),
}

// One row of the telegram bucket. Fields that do not apply are "" rather than absent:
// merge_events_by_keys drops events missing any of the merged keys.
internal data class TelegramActivity(
    val app: String,
    val screen: TelegramScreen,
    val chat: String = "",
    val chatType: String = "",
    val mediaType: String = "",
)

internal data class CompletedTelegramSession(
    val activity: TelegramActivity,
    val start: Instant,
    val duration: Duration,
)

private data class Chat(val name: String, val type: ChatType)

private sealed class Classified {
    // `subtitle` is null on screens that show only a title, like comments.
    data class Header(val title: String, val subtitle: String?, val hasMessageField: Boolean) : Classified()
    data class Viewer(val video: Boolean) : Classified()
    object ChatList : Classified()
    object Unrecognized : Classified()
}

// Turns accessibility events and window snapshots of Telegram-like apps into sessions of
// (screen, chat, chat type, media type). A session is returned once it ends: when the
// activity changes, another app takes the foreground, or leave() is called.
//
// Works from events alone when the window tree cannot be read (event-only mode): list
// clicks give chat name and type, window titles give the chat or the list, the viewer
// window gives media. Snapshots refine that and catch what has no event, like going back
// to the list.
internal class TelegramActivityTracker(
    private val packages: Set<String> = TELEGRAM_PACKAGES,
    private val listTitles: Set<String> = TELEGRAM_LIST_TITLES,
    ignoredPackages: Set<String> = emptySet(),
) {
    // The keyboard and the shade draw over the app without leaving it.
    private val ignoredPackages = ignoredPackages + SYSTEM_UI

    var current: TelegramActivity? = null
        private set
    private var since: Instant = Instant.EPOCH
    private val lastChats = HashMap<String, Chat>()
    // A list row states the type explicitly; the tree only lets us guess it.
    private val clickedTypes = lruMap<ChatType>()
    private val guessedTypes = lruMap<ChatType>()

    fun onEvent(event: UiEvent, now: Instant): CompletedTelegramSession? {
        val pkg = event.packageName ?: return null
        if (pkg in ignoredPackages) return null
        if (pkg !in packages) {
            return if (event.type == UiEventType.WINDOW_STATE_CHANGED) leave(now) else null
        }
        return when (event.type) {
            UiEventType.WINDOW_STATE_CHANGED -> onWindowState(pkg, event, now)
            UiEventType.VIEW_CLICKED -> onClick(pkg, event, now)
            else -> null
        }
    }

    fun onSnapshot(app: String, root: UiNode, now: Instant): CompletedTelegramSession? =
        when (val c = classify(root)) {
            is Classified.Header -> {
                val keep = lastChats[app]?.takeIf { c.subtitle == null && isInChat(app) }
                when {
                    // Comments: the header shows "N comments", the chat is still the channel.
                    keep != null -> moveTo(chatActivity(app, keep), now)
                    c.subtitle == null -> moveTo(chatActivity(app, c.title, null), now)
                    else -> moveTo(chatActivity(app, c.title, guessType(c)), now)
                }
            }
            is Classified.Viewer -> moveTo(mediaActivity(app, c.video), now)
            Classified.ChatList -> moveTo(TelegramActivity(app, TelegramScreen.CHAT_LIST), now)
            // A viewer with hidden controls shows (almost) nothing; it is still the viewer.
            Classified.Unrecognized ->
                if (current?.app == app && current?.screen == TelegramScreen.MEDIA) null
                else moveTo(TelegramActivity(app, TelegramScreen.UNKNOWN), now)
        }

    fun leave(now: Instant): CompletedTelegramSession? {
        val done = complete(now)
        current = null
        return done
    }

    private fun onWindowState(app: String, event: UiEvent, now: Instant): CompletedTelegramSession? {
        val text = event.text?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (event.className?.endsWith(".LaunchActivity") == true) {
            if (text in listTitles) return moveTo(TelegramActivity(app, TelegramScreen.CHAT_LIST), now)
            return moveTo(chatActivity(app, text, null), now)
        }
        if (text.startsWith(VIEWER_TITLE_PREFIX)) {
            return moveTo(mediaActivity(app, video = text.contains(VIEWER_VIDEO_MARKER)), now)
        }
        return null
    }

    private fun onClick(app: String, event: UiEvent, now: Instant): CompletedTelegramSession? {
        // Rows are ViewGroups with a description; message bubbles carry text, not one.
        if (event.className?.endsWith(".ViewGroup") != true) return null
        if (current?.screen == TelegramScreen.MEDIA) return null
        val chat = parseRow(event.contentDescription ?: return null) ?: return null
        clickedTypes[chat.name] = chat.type
        return moveTo(chatActivity(app, chat), now)
    }

    private fun isInChat(app: String): Boolean =
        current?.app == app && (current?.screen == TelegramScreen.CHAT || current?.screen == TelegramScreen.MEDIA)

    // A typing indicator replaces a group's member count for a moment; that must not turn
    // a known group or channel into a direct chat.
    private fun chatActivity(app: String, name: String, observed: ChatType?): TelegramActivity {
        val guessed = guessedTypes[name]
        val type = clickedTypes[name]
            ?: if (observed == null || (observed == ChatType.DIRECT && guessed != null && guessed != ChatType.DIRECT)) {
                guessed ?: ChatType.UNKNOWN
            } else {
                observed.also { guessedTypes[name] = it }
            }
        return chatActivity(app, Chat(name, type))
    }

    private fun chatActivity(app: String, chat: Chat): TelegramActivity {
        lastChats[app] = chat
        return TelegramActivity(app, TelegramScreen.CHAT, chat.name, chat.type.key)
    }

    private fun mediaActivity(app: String, video: Boolean): TelegramActivity {
        val chat = lastChats[app]
        return TelegramActivity(
            app,
            TelegramScreen.MEDIA,
            chat = chat?.name ?: "",
            chatType = chat?.type?.key ?: "",
            mediaType = if (video) "video" else "image",
        )
    }

    private fun moveTo(next: TelegramActivity, now: Instant): CompletedTelegramSession? {
        if (next == current) return null
        val done = complete(now)
        current = next
        since = now
        return done
    }

    private fun complete(now: Instant): CompletedTelegramSession? = current?.let {
        // The clock can step backward (NTP, manual change); never report a negative duration.
        CompletedTelegramSession(it, since, Duration.between(since, now).coerceAtLeast(Duration.ZERO))
    }
}

private fun <V> lruMap(): MutableMap<String, V> =
    object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > MAX_REMEMBERED_CHATS
    }

private fun parseRow(description: String): Chat? {
    val (prefix, type) = ROW_PREFIXES.firstOrNull { description.startsWith(it.first) } ?: ("" to ChatType.DIRECT)
    val rest = description.removePrefix(prefix)
    val end = rest.indexOf(". ")
    if (end <= 0) return null
    return Chat(rest.substring(0, end), type)
}

// A digit-led subtitle is a member or subscriber count, which direct chats do not have;
// of those, only groups let a regular member write.
private fun guessType(header: Classified.Header): ChatType = when {
    header.subtitle?.firstOrNull()?.isDigit() != true -> ChatType.DIRECT
    header.hasMessageField -> ChatType.GROUP
    else -> ChatType.CHANNEL
}

private fun classify(root: UiNode): Classified {
    var header: Classified.Header? = null
    var viewerDate = false
    var video = false
    var listLogo = false
    var messageField = false
    val pending = ArrayDeque<UiNode>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        when (node.className) {
            EDIT_TEXT -> messageField = true
            // The viewer's date line repeats its text as the description; nothing else does.
            TEXT_VIEW -> if (!node.text.isNullOrEmpty() && node.text == node.contentDescription) viewerDate = true
            IMAGE_VIEW -> if (node.contentDescription == LIST_LOGO_DESCRIPTION) listLogo = true
            in VIDEO_CLASSES -> video = true
        }
        if (header == null) header = findHeader(node)
        pending.addAll(node.children)
    }
    return when {
        header != null -> header.copy(hasMessageField = messageField)
        viewerDate -> Classified.Viewer(video)
        listLogo -> Classified.ChatList
        else -> Classified.Unrecognized
    }
}

// The chat header: a clickable FrameLayout right after the clickable back arrow, holding
// the title and subtitle TextViews. Matched by structure because the app sets no view ids
// and every label is localized.
private fun findHeader(parent: UiNode): Classified.Header? {
    val kids = parent.children
    for (i in 1 until kids.size) {
        val back = kids[i - 1]
        val bar = kids[i]
        if (back.className != IMAGE_VIEW || !back.clickable) continue
        if (bar.className != FRAME_LAYOUT || !bar.clickable) continue
        val texts = bar.children.filter { it.className == TEXT_VIEW }.mapNotNull { it.text?.takeIf(String::isNotBlank) }
        if (texts.isNotEmpty()) return Classified.Header(texts[0], texts.getOrNull(1), hasMessageField = false)
    }
    return null
}
