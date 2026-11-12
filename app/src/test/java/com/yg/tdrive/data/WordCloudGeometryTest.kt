package com.yg.tgdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordCloudGeometryTest {
    private fun words() = listOf(
        WordCloudGeometry.Word("big", 120f, 60f),
        WordCloudGeometry.Word("medium", 70f, 34f),
        WordCloudGeometry.Word("small", 40f, 20f),
        WordCloudGeometry.Word("tiny", 26f, 14f),
    )

    @Test fun biggestWordSitsNearCenter() {
        val placed = WordCloudGeometry.layout(words(), 400f, 300f)
        val big = placed.first { it.text == "big" }
        val cx = big.left + big.width / 2
        val cy = big.top + big.height / 2
        assertTrue("大词应靠近画布中心", kotlin.math.abs(cx - 200f) < 40f)
        assertTrue("大词应靠近画布中心", kotlin.math.abs(cy - 150f) < 40f)
    }

    @Test fun placedWordsNeverOverlap() {
        val many = (1..18).map { WordCloudGeometry.Word("w$it", 20f + it * 3f, 12f + it * 2f) }
        val placed = WordCloudGeometry.layout(many, 360f, 240f)
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val a = placed[i]
            val b = placed[j]
            val overlap = a.left < b.left + b.width && b.left < a.left + a.width &&
                a.top < b.top + b.height && b.top < a.top + a.height
            assertFalse("${a.text} 与 ${b.text} 重叠", overlap)
        }
    }

    @Test fun everyWordStaysInsideCanvas() {
        val placed = WordCloudGeometry.layout(words(), 300f, 200f)
        placed.forEach {
            assertTrue("${it.text} 左边越界", it.left >= 0f)
            assertTrue("${it.text} 上边越界", it.top >= 0f)
            assertTrue("${it.text} 右边越界", it.left + it.width <= 300f)
            assertTrue("${it.text} 下边越界", it.top + it.height <= 200f)
        }
    }

    @Test fun wordTallerThanCanvasIsDropped() {
        val placed = WordCloudGeometry.layout(
            listOf(WordCloudGeometry.Word("huge", 200f, 400f), WordCloudGeometry.Word("ok", 30f, 16f)),
            300f, 200f,
        )
        assertEquals(listOf("ok"), placed.map { it.text })
    }

    @Test fun allWordsPlacedWhenCanvasIsRoomy() {
        val placed = WordCloudGeometry.layout(words(), 1200f, 900f)
        assertEquals(4, placed.size)
    }
}
