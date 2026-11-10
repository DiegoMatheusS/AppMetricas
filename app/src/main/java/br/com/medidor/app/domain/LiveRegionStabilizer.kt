package br.com.medidor.app.domain

/**
 * A stable count alone is insufficient: different regions can appear in each
 * frame while the number stays unchanged. Require spatially consistent,
 * similarly sized components before fixing a camera frame.
 */
internal class LiveRegionStabilizer(
    private val confirmations: Int = 3,
    private val maximumMovementFraction: Double = 0.06
) {
    init {
        require(confirmations > 0)
        require(maximumMovementFraction > 0 && maximumMovementFraction < 1)
    }

    private var previous: List<CountRegion>? = null
    private var streak = 0

    fun update(regions: List<CountRegion>, width: Int, height: Int): Boolean {
        require(width > 0 && height > 0)
        if (regions.isEmpty()) {
            reset()
            return false
        }
        val old = previous
        val consistent = old != null && old.size == regions.size &&
            sameRegions(old, regions, width, height)
        streak = if (consistent) streak + 1 else 1
        previous = regions.toList()
        return streak >= confirmations
    }

    fun reset() {
        previous = null
        streak = 0
    }

    private fun sameRegions(
        old: List<CountRegion>,
        current: List<CountRegion>,
        width: Int,
        height: Int
    ): Boolean {
        val used = BooleanArray(current.size)
        return old.all { before ->
            val bx = (before.left + before.right) / 2.0 / width
            val by = (before.top + before.bottom) / 2.0 / height
            val best = current.indices
                .filter { !used[it] }
                .map { index ->
                    val after = current[index]
                    val dx = bx - (after.left + after.right) / 2.0 / width
                    val dy = by - (after.top + after.bottom) / 2.0 / height
                    val ratio = after.area.toDouble() / before.area.coerceAtLeast(1)
                    index to if (ratio in 0.65..1.55) dx * dx + dy * dy else Double.POSITIVE_INFINITY
                }
                .minByOrNull { it.second }
                ?: return@all false
            if (best.second > maximumMovementFraction * maximumMovementFraction) {
                false
            } else {
                used[best.first] = true
                true
            }
        }
    }
}
