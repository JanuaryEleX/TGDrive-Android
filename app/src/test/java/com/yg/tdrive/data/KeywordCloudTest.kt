package com.yg.tgdrive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordCloudTest {
    @Test fun `common words are counted once per filename and ranked by frequency`() {
        val words = KeywordCloud.fromNames(listOf("海边旅行-01.mp4", "海边旅行-02.mp4", "海边日落.jpg", "旅行照片.png"))
        assertEquals(3, words.first { it.text == "海边" }.count)
        assertEquals(3, words.first { it.text == "旅行" }.count)
    }

    @Test fun `noise extensions serials and generic words are excluded`() {
        val words = KeywordCloud.fromNames(listOf("VID_20241113_001.mp4", "IMG_9999.jpg", "视频_海边_01.mp4", "海边_02.mp4"))
        assertTrue(words.any { it.text == "海边" })
        assertTrue(words.none { it.text in setOf("视频", "VID", "IMG", "mp4", "jpg", "001", "02") })
    }

    @Test fun `empty or nonsemantic names have no suggestions`() {
        assertTrue(KeywordCloud.fromNames(listOf("123456.mp4", "IMG_001.jpg")).isEmpty())
    }

    @Test fun `cloud is capped and deterministic`() {
        val names = listOf("旅行日落山川星空.mp4", "旅行日落山川星空.jpg")
        val words = KeywordCloud.fromNames(names, limit = 4)
        assertEquals(4, words.size)
        assertEquals(words, KeywordCloud.fromNames(names, limit = 4))
    }
}
