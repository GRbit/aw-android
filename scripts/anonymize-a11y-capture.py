#!/usr/bin/env python3
"""Anonymize accessibility captures of Telegram-like apps for use as test fixtures.

Usage: anonymize-a11y-capture.py OUT_DIR CAPTURE...

A capture is either a `uiautomator dump` XML file or a `uiautomator events` log.
XML dumps keep their structure; only text, content-desc and hint are rewritten.
Event logs are reduced to a TSV with the fields the watcher reads (see EVENT_TYPES).

User content (chat names, messages, folder names) becomes the first 8 hex digits of its
sha1, so the same name hashes identically across all files of one run and tests can
still follow a chat from a list click to its header. UI chrome, dates, times and
durations are kept, because the classifier depends on them; counts in chat subtitles
become 100 so member counts do not identify a chat.
"""

import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET

# Whole strings that are UI chrome in the captured apps (English and Russian UI).
KEEP_STRINGS = {
    "All", "Application icon", "Attach media", "Back", "Back, Switch input method",
    "Bot commands", "Call", "Chats", "Clear All", "Close", "Contacts", "Emoji, stickers, and GIFs",
    "Forward", "Gift", "Go back", "Go to bottom", "Go back, Switch to fullscreen",
    "KISS launcher", "Leave a comment", "Message", "Comment", "More options",
    "Notification shade.", "Permission request", "Photo", "Pinned Message",
    "Pinned message list", "Profile", "Profile photo", "Recent apps", "Record video message",
    "Search", "Search Chats", "Settings", "Share", "Switch to fullscreen", "Telegram",
    "Fork Client", "Unmute", "Web tabs ", "Reply", "Vinyl Music Player",
    "Назад", "Поиск", "Подарок", "Включить звук", "Фотография профиля",
    "Дополнительные параметры", "Эмодзи, стикеры и GIF", "Сообщение", "Прикрепить медиа",
    "Записать видеосообщение", "Позвонить", "Закреплённое сообщение",
    "Список закреплённых сообщений", "Команды бота", "Перейти в конец", "Подать заявку",
    "Чаты", "Контакты", "Настройки", "Профиль", "Комментарий", "Оставить комментарий",
    "Поделиться", "Фото",
}

# Words allowed inside a kept segment next to digits and punctuation: list-row status,
# chat subtitles, viewer dates and player positions.
KEEP_WORDS = {
    "channel", "group", "muted", "online", "received", "at", "last", "seen", "recently",
    "subscribers", "subscriber", "members", "member", "comment", "comments", "new",
    "message", "messages", "unread", "chat", "chats", "today", "yesterday", "of",
    "minutes", "minute", "seconds", "second", "premium", "account", "viewed", "time",
    "times", "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov",
    "dec", "january", "february", "march", "april", "june", "july", "august",
    "september", "october", "november", "december", "pinned", "photo", "video",
    "канал", "группа", "без", "уведомлений", "в", "сети", "получено", "был", "была",
    "была(а)", "был(а)", "недавно", "подписчиков", "подписчика", "подписчик",
    "участников", "участника", "участник", "комментарий", "комментария", "комментариев",
    "новых", "новое", "сообщений", "сообщения", "сегодня", "вчера", "из", "мин", "сек",
    "premium-аккаунт", "фото", "видео", "закреплённое",
}

# Segments with these words carry counts that could identify a chat.
COUNT_WORDS = {
    "subscribers", "subscriber", "members", "member", "online", "подписчиков",
    "подписчика", "подписчик", "участников", "участника", "участник", "сети",
}

HASH_RE = re.compile(r"^[0-9a-f]{8}$")
SEGMENT_SPLIT_RE = re.compile(r"(\n|\. |, )")
WORD_RE = re.compile(r"[^\W\d_]+(?:[-(][^\W\d_]+\)?)*", re.UNICODE)
NUMBER_RE = re.compile(r"\d[\d  ]*\d|\d")

# Chat list rows: "Channel. <name>. ...", "Group. <name>. ...", direct: "<name>. ...".
ROW_PREFIXES = ("Channel. ", "Group. ", "Канал. ", "Группа. ")

EVENT_TYPES = {
    "TYPE_WINDOW_STATE_CHANGED": "STATE",
    "TYPE_WINDOW_CONTENT_CHANGED": "CONTENT",
    "TYPE_WINDOWS_CHANGED": "WINDOWS",
    "TYPE_VIEW_CLICKED": "CLICKED",
}
# Only these event types carry text the watcher reads. Content-change payloads hold
# message previews, so they are dropped rather than hashed.
EVENT_TYPES_WITH_TEXT = {"STATE", "CLICKED"}
IGNORED_TEXT_PACKAGES = {"com.android.systemui"}

EVENT_RE = re.compile(
    r"^(?P<ts>\d\d-\d\d \d\d:\d\d:\d\d\.\d+) EventType: (?P<type>\w+);.*?"
    r"PackageName: (?P<pkg>[^;]*);.*?\[ ClassName: (?P<cls>[^;]*); Text: \[(?P<text>.*?)\]; "
    r"ContentDescription: (?P<cd>.*?); ItemCount:"
)


def sha8(s):
    return hashlib.sha1(s.encode("utf-8")).hexdigest()[:8]


class Anonymizer:
    def __init__(self):
        self.names = set()

    def learn_name(self, name):
        name = name.strip()
        if name and name not in KEEP_STRINGS and not self.is_kept_segment(name):
            self.names.add(name)

    def learn_row_description(self, cd):
        for prefix in ROW_PREFIXES:
            if cd.startswith(prefix):
                cd = cd[len(prefix):]
                break
        self.learn_name(cd.split(". ", 1)[0])

    @staticmethod
    def is_kept_segment(seg):
        if HASH_RE.match(seg):
            return True
        words = WORD_RE.findall(seg)
        if not words:
            # Pure digits, times and punctuation: dates, "00:27 / 00:42".
            return not re.search(r"[^\W_]", re.sub(r"\d", "", seg))
        return all(w.lower() in KEEP_WORDS for w in words)

    @staticmethod
    def blur_counts(seg):
        if any(w.lower() in COUNT_WORDS for w in WORD_RE.findall(seg)):
            return NUMBER_RE.sub("100", seg)
        return seg

    def anonymize(self, s):
        if not s or s in KEEP_STRINGS:
            return s
        for name in sorted(self.names, key=len, reverse=True):
            if name in s:
                s = s.replace(name, sha8(name))
        if s in KEEP_STRINGS:
            return s
        parts = SEGMENT_SPLIT_RE.split(s)
        out = []
        for i, part in enumerate(parts):
            if i % 2 == 1 or part == "":
                out.append(part)
            elif part in KEEP_STRINGS or self.is_kept_segment(part):
                out.append(self.blur_counts(part))
            else:
                out.append(sha8(part))
        return "".join(out)


def is_xml(path):
    with open(path, "rb") as f:
        return f.read(5) == b"<?xml"


def header_titles(root):
    """Chat header titles: first TextView child of a clickable FrameLayout that follows a
    clickable ImageView (the back button)."""
    for parent in root.iter("node"):
        kids = parent.findall("node")
        for prev, cur in zip(kids, kids[1:]):
            if (prev.get("class", "").endswith("ImageView") and prev.get("clickable") == "true"
                    and cur.get("class", "").endswith("FrameLayout")
                    and cur.get("clickable") == "true"):
                texts = [k.get("text") for k in cur.findall("node")
                         if k.get("class", "").endswith("TextView") and k.get("text")]
                if texts:
                    yield texts[0]


def viewer_titles(root):
    """Media viewer title: the TextView sibling group preceding the date TextView."""
    for parent in root.iter("node"):
        kids = parent.findall("node")
        for prev, cur in zip(kids, kids[1:]):
            if cur.get("class", "").endswith("TextView") and cur.get("text") \
                    and cur.get("text") == cur.get("content-desc"):
                for n in prev.iter("node"):
                    if n.get("class", "").endswith("TextView") and n.get("text"):
                        yield n.get("text")
                        break


def parse_events(path):
    events = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = EVENT_RE.match(line)
            if not m or m.group("type") not in EVENT_TYPES:
                continue
            cd = m.group("cd")
            events.append({
                "ts": m.group("ts"),
                "type": EVENT_TYPES[m.group("type")],
                "pkg": m.group("pkg"),
                "cls": m.group("cls"),
                "text": m.group("text"),
                "cd": "" if cd == "null" else cd,
            })
    return events


def ts_ms(ts):
    h, m, s = ts.split(" ")[1].split(":")
    return int((int(h) * 3600 + int(m) * 60 + float(s)) * 1000)


def escape(s):
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out_dir, paths = sys.argv[1], sys.argv[2:]
    anon = Anonymizer()
    xmls, logs = {}, {}
    for p in paths:
        if is_xml(p):
            xmls[p] = ET.parse(p)
            for t in header_titles(xmls[p].getroot()):
                anon.learn_name(t)
        else:
            logs[p] = parse_events(p)
            for e in logs[p]:
                if e["type"] == "STATE" and e["cls"].endswith("LaunchActivity"):
                    anon.learn_name(e["text"])
                if e["type"] == "CLICKED" and e["cls"].endswith("ViewGroup") and e["cd"]:
                    anon.learn_row_description(e["cd"])
    for tree in xmls.values():
        for t in viewer_titles(tree.getroot()):
            anon.learn_name(t)

    os.makedirs(out_dir, exist_ok=True)
    for p, tree in xmls.items():
        for n in tree.getroot().iter("node"):
            for attr in ("text", "content-desc", "hint"):
                if n.get(attr):
                    n.set(attr, anon.anonymize(n.get(attr)))
        dst = os.path.join(out_dir, os.path.basename(p))
        tree.write(dst, encoding="UTF-8", xml_declaration=True)
    for p, events in logs.items():
        dst = os.path.join(out_dir, os.path.splitext(os.path.basename(p))[0] + ".tsv")
        t0 = ts_ms(events[0]["ts"]) if events else 0
        with open(dst, "w", encoding="utf-8") as f:
            f.write("# ms\ttype\tpackage\tclass\ttext\tcontent_description\n")
            for e in events:
                keep = e["type"] in EVENT_TYPES_WITH_TEXT and e["pkg"] not in IGNORED_TEXT_PACKAGES
                text = anon.anonymize(e["text"]) if keep else ""
                cd = anon.anonymize(e["cd"]) if keep else ""
                f.write("\t".join([str(ts_ms(e["ts"]) - t0), e["type"], e["pkg"], e["cls"],
                                   escape(text), escape(cd)]) + "\n")


if __name__ == "__main__":
    main()
