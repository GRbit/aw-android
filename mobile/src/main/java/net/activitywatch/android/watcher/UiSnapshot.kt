package net.activitywatch.android.watcher

import android.view.accessibility.AccessibilityNodeInfo

// Immutable copy of the part of an accessibility tree an app driver classifies. Copying
// once lets the classifier inspect nodes freely without binder calls or recycle rules.
internal data class UiNode(
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val scrollable: Boolean,
    val selected: Boolean,
    val children: List<UiNode>,
)

internal class UiSnapshot(val root: UiNode, val nodeCount: Int, val truncated: Boolean)

// NodeOps plus the node properties a snapshot copies. The XML adapter in the unit tests
// is the second implementation.
internal interface SnapshotOps<T : Any> : NodeOps<T> {
    fun className(node: T): String?
    fun text(node: T): String?
    fun contentDescription(node: T): String?
    fun isClickable(node: T): Boolean
    fun isScrollable(node: T): Boolean
    fun isSelected(node: T): Boolean
}

internal object AccessibilityNodeSnapshotOps : SnapshotOps<AccessibilityNodeInfo> {
    override fun childCount(node: AccessibilityNodeInfo): Int = node.childCount
    override fun getChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = node.getChild(index)
    override fun recycle(node: AccessibilityNodeInfo) = node.recycle()
    override fun className(node: AccessibilityNodeInfo): String? = node.className?.toString()
    override fun text(node: AccessibilityNodeInfo): String? = node.text?.toString()
    override fun contentDescription(node: AccessibilityNodeInfo): String? = node.contentDescription?.toString()
    override fun isClickable(node: AccessibilityNodeInfo): Boolean = node.isClickable
    override fun isScrollable(node: AccessibilityNodeInfo): Boolean = node.isScrollable
    override fun isSelected(node: AccessibilityNodeInfo): Boolean = node.isSelected
}

// Copies `root` and its descendants, except the contents of scrollable descendants: those
// are message and chat lists, the bulk of the tree and the part that holds private text,
// so they are kept as empty placeholders and their children are never requested.
//
// Same bounds and recycle contract as traverse() in AccessibilityNodeTraversal.kt:
// MAX_TRAVERSAL_NODES getChild() calls, MAX_TRAVERSAL_DEPTH levels, every obtained child
// recycled once, `root` left to the caller. Recursion is safe here because the depth is
// capped at MAX_TRAVERSAL_DEPTH frames.
internal fun <T : Any> buildSnapshot(root: T, ops: SnapshotOps<T>): UiSnapshot {
    val builder = SnapshotBuilder(ops)
    val node = builder.copy(root, depth = 0)
    return UiSnapshot(node, builder.nodes, builder.truncated)
}

private class SnapshotBuilder<T : Any>(private val ops: SnapshotOps<T>) {
    var obtained = 1
    var nodes = 0
    var truncated = false

    fun copy(node: T, depth: Int): UiNode {
        nodes++
        val scrollable = ops.isScrollable(node)
        val pruned = scrollable && depth > 0
        return UiNode(
            className = ops.className(node),
            text = if (pruned) null else ops.text(node),
            contentDescription = if (pruned) null else ops.contentDescription(node),
            clickable = ops.isClickable(node),
            scrollable = scrollable,
            selected = ops.isSelected(node),
            children = if (pruned) emptyList() else copyChildren(node, depth),
        )
    }

    private fun copyChildren(node: T, depth: Int): List<UiNode> {
        val count = ops.childCount(node)
        if (count == 0) return emptyList()
        val out = ArrayList<UiNode>(count)
        for (i in 0 until count) {
            if (obtained >= MAX_TRAVERSAL_NODES) {
                truncated = true
                break
            }
            obtained++
            val child = ops.getChild(node, i) ?: continue
            try {
                if (depth + 1 >= MAX_TRAVERSAL_DEPTH) {
                    truncated = true
                } else {
                    out.add(copy(child, depth + 1))
                }
            } finally {
                ops.recycle(child)
            }
        }
        return out
    }
}
