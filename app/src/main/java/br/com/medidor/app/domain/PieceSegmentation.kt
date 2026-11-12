package br.com.medidor.app.domain

import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sqrt

/** Foreground components with an optional distance watershed for touching exemplars. */
internal object PieceSegmentation {
    fun components(mask: BooleanArray, width: Int, height: Int): List<IntArray> {
        require(mask.size == width * height)
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val result = mutableListOf<IntArray>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0
            var tail = 1
            queue[0] = start
            seen[start] = true
            while (head < tail) {
                val index = queue[head++]
                for (neighbor in neighbors(index, width, height)) {
                    if (mask[neighbor] && !seen[neighbor]) {
                        seen[neighbor] = true
                        queue[tail++] = neighbor
                    }
                }
            }
            result += queue.copyOf(tail)
        }
        return result
    }

    fun split(component: IntArray, width: Int, height: Int, referenceArea: Double): List<IntArray> {
        if (!referenceArea.isFinite() || referenceArea < 24 ||
            component.size < referenceArea * 1.65 || component.size > referenceArea * 12) return listOf(component)
        val inside = BooleanArray(width * height)
        component.forEach { inside[it] = true }
        val distance = IntArray(inside.size)
        val boundary = ArrayDeque<Int>()
        component.forEach { index ->
            if (neighbors(index, width, height).any { !inside[it] }) {
                distance[index] = 1
                boundary.addLast(index)
            }
        }
        while (boundary.isNotEmpty()) {
            val index = boundary.removeFirst()
            for (neighbor in neighbors(index, width, height)) {
                if (inside[neighbor] && distance[neighbor] == 0) {
                    distance[neighbor] = distance[index] + 1
                    boundary.addLast(neighbor)
                }
            }
        }
        val radius = sqrt(referenceArea / PI)
        val expected = (component.size / referenceArea).toInt().coerceIn(2, 12)
        val peaks = mutableListOf<Int>()
        for (index in component.sortedByDescending { distance[it] }) {
            if (distance[index] < radius * .45) break
            if (peaks.all { hypot((it % width - index % width).toDouble(),
                    (it / width - index / width).toDouble()) >= radius * 1.25 } &&
                neighbors(index, width, height).all { distance[it] <= distance[index] }) peaks += index
            if (peaks.size == expected) break
        }
        if (peaks.size < 2) return listOf(component)
        val labels = IntArray(inside.size) { -1 }
        data class Entry(val index: Int, val label: Int)
        val pending = PriorityQueue<Entry>(compareByDescending<Entry> { distance[it.index] }
            .thenBy { it.index })
        peaks.forEachIndexed { label, index ->
            labels[index] = label
            pending.add(Entry(index, label))
        }
        while (pending.isNotEmpty()) {
            val current = pending.remove()
            for (neighbor in neighbors(current.index, width, height)) {
                if (inside[neighbor] && labels[neighbor] == -1) {
                    labels[neighbor] = current.label
                    pending.add(Entry(neighbor, current.label))
                }
            }
        }
        val parts = List(peaks.size) { mutableListOf<Int>() }
        component.forEach { index -> if (labels[index] >= 0) parts[labels[index]].add(index) }
        // Reject an unsupported split instead of converting a large object to an arbitrary count.
        if (parts.any { it.size !in (referenceArea * .45).toInt()..(referenceArea * 1.65).toInt() })
            return listOf(component)
        return parts.map { it.toIntArray() }
    }

    private fun neighbors(index: Int, width: Int, height: Int): IntArray {
        val x = index % width
        val y = index / width
        return intArrayOf(if (x > 0) index - 1 else -1, if (x + 1 < width) index + 1 else -1,
            if (y > 0) index - width else -1, if (y + 1 < height) index + width else -1)
            .filter { it >= 0 }.toIntArray()
    }
}
