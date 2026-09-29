package net.activitywatch.android.watcher

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

// Test adapter for the snapshot seam: reads `uiautomator dump` XML (see
// scripts/anonymize-a11y-capture.py) the same way the service reads AccessibilityNodeInfo.
internal object XmlDumpOps : SnapshotOps<Element> {
    private fun children(node: Element): List<Element> {
        val out = ArrayList<Element>()
        val list = node.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child is Element && child.tagName == "node") out.add(child)
        }
        return out
    }

    private fun attr(node: Element, name: String): String? = node.getAttribute(name).ifEmpty { null }

    override fun childCount(node: Element): Int = children(node).size
    override fun getChild(node: Element, index: Int): Element? = children(node).getOrNull(index)
    override fun recycle(node: Element) {}
    override fun className(node: Element): String? = attr(node, "class")
    override fun text(node: Element): String? = attr(node, "text")
    override fun contentDescription(node: Element): String? = attr(node, "content-desc")
    override fun isClickable(node: Element): Boolean = node.getAttribute("clickable") == "true"
    override fun isScrollable(node: Element): Boolean = node.getAttribute("scrollable") == "true"
    override fun isSelected(node: Element): Boolean = node.getAttribute("selected") == "true"
}

// The window root element of a fixture under src/test/resources/telegram.
internal fun loadDumpRoot(name: String): Element {
    val stream = XmlDumpOps::class.java.classLoader!!.getResourceAsStream("telegram/$name")
        ?: error("missing fixture telegram/$name")
    val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
    val hierarchy = doc.documentElement
    val list = hierarchy.childNodes
    for (i in 0 until list.length) {
        val child = list.item(i)
        if (child is Element && child.tagName == "node") return child
    }
    error("fixture telegram/$name has no window root")
}

internal fun loadDump(name: String): UiSnapshot = buildSnapshot(loadDumpRoot(name), XmlDumpOps)
