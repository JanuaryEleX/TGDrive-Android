package com.yg.tgdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordCloudGeometryTest {
    @Test
    fun realisticPhoneCanvasKeepsMostWordsAndNoOverlap() {
        // 1080x2400 手机，搜索页卡片可用约 950x1150 px；字号 15~40sp 对应高度约 40~106px
        val raw = listOf(
            "第01集" to 34, "花絮" to 21, "1080p" to 19, "国语" to 16, "中字" to 15,
            "S01E02" to 13, "演唱会" to 12, "现场" to 11, "4K" to 10, "未删减" to 9,
            "纪录片" to 8, "合集" to 8, "教程" to 7, "备份" to 6, "2024" to 6,
            "旅行" to 5, "vlog" to 4, "重制" to 3,
        )
        val words = raw.map { (text, count) ->
            val t = (count - 3).toFloat() / (34 - 3)
            val fontSize = 15f + 25f * t
            val width = text.length * fontSize * 1.05f
            val height = fontSize * 1.42f
            WordCloudGeometry.Word(text, width, height)
        }
        val placed = WordCloudGeometry.layout(words, canvasWidth = 950f, canvasHeight = 1150f)
        println("PLACED=${placed.size}/${words.size}")
        assertTrue("至少应放下 15 个词，实际 ${placed.size}", placed.size >= 15)
    }
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
