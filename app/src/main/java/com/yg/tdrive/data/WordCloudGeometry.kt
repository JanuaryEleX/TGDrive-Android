package com.yg.tgdrive.data

import kotlin.math.cos
import kotlin.math.sin

/**
 * 词云布局：按占用面积从大到小，用阿基米德螺旋从中心向外找不重叠的位置。
 *
 * 纯几何计算（单位与调用方一致，Compose 里传 px），便于单测；不做任何绘制。
 */
object WordCloudGeometry {
    data class Word(val text: String, val width: Float, val height: Float)

    data class Placed(
        val text: String,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    ) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f
    }

    /** 词与词之间的最小留白。 */
    private const val GAP = 3f

    fun layout(
        words: List<Word>,
        canvasWidth: Float,
        canvasHeight: Float,
        maxSteps: Int = 4000,
    ): List<Placed> {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return emptyList()
        val centerX = canvasWidth / 2f
        val centerY = canvasHeight / 2f
        val placed = ArrayList<Placed>(words.size)
        words.sortedByDescending { it.width * it.height }.forEach { word ->
            if (word.width <= 0f || word.height <= 0f) return@forEach
            place(word, centerX, centerY, canvasWidth, canvasHeight, placed, maxSteps)?.let(placed::add)
        }
        return placed
    }

    private fun place(
        word: Word,
        centerX: Float,
        centerY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        placed: List<Placed>,
        maxSteps: Int,
    ): Placed? {
        var step = 0
        while (step < maxSteps) {
            val angle = step * 0.45f
            val radius = 2.6f * angle
            val left = centerX + radius * cos(angle) - word.width / 2f
            val top = centerY + radius * sin(angle) - word.height / 2f
            val inside = left >= 0f && top >= 0f &&
                left + word.width <= canvasWidth && top + word.height <= canvasHeight
            if (inside && placed.none { overlaps(it, left, top, word) }) {
                return Placed(word.text, left, top, word.width, word.height)
            }
            step++
        }
        return null
    }

    private fun overlaps(other: Placed, left: Float, top: Float, word: Word): Boolean =
        left < other.right + GAP && other.left - GAP < left + word.width &&
            top < other.bottom + GAP && other.top - GAP < top + word.height
}
