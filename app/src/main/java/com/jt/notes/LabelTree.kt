package com.jt.notes

import com.jt.notes.data.Label

/**
 * The pure rules for nested labels, kept free of Android so they can be reasoned
 * about on their own. Every walk is guarded against cycles, so a damaged
 * database can never loop forever.
 */
object LabelTree {
    /** How deep the indent goes on screen. Deeper labels still nest; they just stop indenting. */
    const val MAX_INDENT_DEPTH = 4

    /** Every label under [rootId], at any depth. */
    fun descendantIds(labels: List<Label>, rootId: Long): Set<Long> {
        val byParent = labels.groupBy { it.parentId }
        val out = HashSet<Long>()
        val stack = ArrayDeque<Long>()
        stack.add(rootId)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (child in byParent[current].orEmpty()) {
                if (out.add(child.id)) stack.add(child.id)
            }
        }
        return out
    }

    /** How many parents sit above this label. 0 is top level. */
    fun depth(labels: List<Label>, id: Long): Int {
        val byId = labels.associateBy { it.id }
        var hops = 0
        val seen = HashSet<Long>()
        var current = byId[id]?.parentId
        while (current != null && seen.add(current) && hops <= MAX_INDENT_DEPTH + 1) {
            hops++
            current = byId[current]?.parentId
        }
        return hops
    }

    /** Whether [parentId] may become the parent of [labelId]: no self-parenting, no cycles. */
    fun canParent(labels: List<Label>, labelId: Long, parentId: Long?): Boolean {
        if (parentId == null) return true
        if (parentId == labelId) return false
        if (labels.none { it.id == parentId }) return false
        return parentId !in descendantIds(labels, labelId)
    }

    /** Labels in display order: top level first, each followed by its children, with depths attached. */
    fun ordered(labels: List<Label>): List<Pair<Label, Int>> {
        val byId = labels.associateBy { it.id }
        val children = labels.filter { it.parentId != null && byId.containsKey(it.parentId) }
            .groupBy { it.parentId }
        val roots = labels.filter { it.parentId == null || !byId.containsKey(it.parentId) }
            .sortedBy { it.name.lowercase() }
        val out = ArrayList<Pair<Label, Int>>()
        val seen = HashSet<Long>()
        fun visit(label: Label, depth: Int) {
            if (!seen.add(label.id)) return
            out.add(label to depth)
            children[label.id].orEmpty().sortedBy { it.name.lowercase() }
                .forEach { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }
        // Anything unreachable (a cycle with no root) still shows, at the top level.
        labels.filter { it.id !in seen }.sortedBy { it.name.lowercase() }
            .forEach { out.add(it to 0) }
        return out
    }
}
