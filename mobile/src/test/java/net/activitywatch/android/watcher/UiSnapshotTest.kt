package net.activitywatch.android.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class TreeNode(
    val className: String = "android.view.View",
    val text: String? = null,
    val scrollable: Boolean = false,
    vararg children: TreeNode?,
) {
    val children: List<TreeNode?> = children.toList()
    var recycleCount = 0
}

private class TreeOps : SnapshotOps<TreeNode> {
    var getChildCalls = 0
    override fun childCount(node: TreeNode): Int = node.children.size
    override fun getChild(node: TreeNode, index: Int): TreeNode? {
        getChildCalls++
        return node.children[index]
    }
    override fun recycle(node: TreeNode) { node.recycleCount++ }
    override fun className(node: TreeNode): String? = node.className
    override fun text(node: TreeNode): String? = node.text
    override fun contentDescription(node: TreeNode): String? = null
    override fun isClickable(node: TreeNode): Boolean = false
    override fun isScrollable(node: TreeNode): Boolean = node.scrollable
    override fun isSelected(node: TreeNode): Boolean = false
}

private fun UiNode.all(): List<UiNode> = listOf(this) + children.flatMap { it.all() }

class UiSnapshotTest {

    @Test
    fun keepsHeaderAndDropsScrollableSubtrees() {
        val snapshot = loadDump("forkgram-en-channel.xml")
        val texts = snapshot.root.all().mapNotNull { it.text }
        // Chat header title and subtitle survive.
        assertTrue(texts.contains("071069b5"))
        assertTrue(texts.contains("100 subscribers"))
        // Message bubbles live in the scrollable RecyclerView and must never be read.
        assertFalse(texts.any { it.startsWith("Photo") })
        val scrollables = snapshot.root.all().filter { it.scrollable }
        assertTrue(scrollables.isNotEmpty())
        assertTrue(scrollables.all { it.children.isEmpty() && it.text == null && it.contentDescription == null })
        assertFalse(snapshot.truncated)
        assertEquals(snapshot.root.all().size, snapshot.nodeCount)
    }

    @Test
    fun readsNodeProperties() {
        val nodes = loadDump("forkgram-en-channel.xml").root.all()
        val back = nodes.single { it.contentDescription == "Go back" }
        assertEquals("android.widget.ImageView", back.className)
        assertTrue(back.clickable)
        val folders = loadDump("forkgram-en-list.xml").root.all()
        assertTrue(folders.any { it.contentDescription == "Telegram" })
    }

    @Test
    fun prunedChildrenAreNeverRequested() {
        val ops = TreeOps()
        val list = TreeNode("RecyclerView", null, true, TreeNode(text = "secret"), TreeNode(text = "secret"))
        val root = TreeNode("FrameLayout", null, false, TreeNode(text = "title"), list)
        val snapshot = buildSnapshot(root, ops)
        assertEquals(2, ops.getChildCalls)
        assertEquals(listOf("title"), snapshot.root.all().mapNotNull { it.text })
        assertEquals(3, snapshot.nodeCount)
    }

    @Test
    fun recyclesEveryObtainedChildButNotTheRoot() {
        val leaf = TreeNode(text = "leaf")
        val mid = TreeNode("LinearLayout", null, false, leaf, null)
        val pruned = TreeNode("RecyclerView", null, true, TreeNode())
        val root = TreeNode("FrameLayout", null, false, mid, pruned)
        buildSnapshot(root, TreeOps())
        assertEquals(0, root.recycleCount)
        assertEquals(listOf(1, 1, 1), listOf(mid, leaf, pruned).map { it.recycleCount })
        assertEquals(0, pruned.children[0]!!.recycleCount)
    }

    @Test
    fun wideTreeStopsAtTheNodeBudget() {
        val ops = TreeOps()
        val root = TreeNode("FrameLayout", null, false, *Array(MAX_TRAVERSAL_NODES + 50) { TreeNode() })
        val snapshot = buildSnapshot(root, ops)
        assertTrue(snapshot.truncated)
        assertEquals(MAX_TRAVERSAL_NODES - 1, ops.getChildCalls)
        assertEquals(MAX_TRAVERSAL_NODES, snapshot.nodeCount)
    }

    @Test
    fun deepTreeStopsAtTheDepthLimit() {
        var node = TreeNode(text = "bottom")
        repeat(MAX_TRAVERSAL_DEPTH + 10) { node = TreeNode("FrameLayout", null, false, node) }
        val snapshot = buildSnapshot(node, TreeOps())
        assertTrue(snapshot.truncated)
        assertEquals(MAX_TRAVERSAL_DEPTH, snapshot.nodeCount)
        assertFalse(snapshot.root.all().any { it.text == "bottom" })
    }
}
