package com.yg.tgdrive.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * caption 位置回归测试。
 *
 * 背景：真机上历史回填跑了 80 页、改名 0 行，「带描述消息」也是 0 —— 原因是 caption
 * 在 content 顶层，而解析器只去 `content.video.caption` 里找。这个 bug 在结果上
 * 只表现为「没有描述」，不会报错，所以必须用真实结构样本钉住。
 */
class CaptionParserTest {

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    /** 真机实测的 messageVideo 结构：caption 与 video 平级。 */
    private val topLevelCaption = obj(
        """
        {"@type":"messageVideo",
         "video":{"duration":60,"file_name":"IMG_2530.MP4","mime_type":"video/mp4"},
         "alternative_videos":[],
         "storyboards":[],
         "start_timestamp":0,
         "caption":{"text":"【新作】Myfans獣会 有男友的高颜值妹妹","entities":[]},
         "show_caption_above_media":false,
         "has_spoiler":false,
         "is_secret":false}
        """.trimIndent(),
    )

    /** 老结构：caption 在媒体对象内部。 */
    private val nestedCaption = obj(
        """
        {"@type":"messageVideo",
         "video":{"duration":60,"file_name":"a.mp4",
                  "caption":{"text":"组内描述","entities":[]}}}
        """.trimIndent(),
    )

    @Test
    fun `顶层 caption 能取到`() {
        assertEquals("【新作】Myfans獣会 有男友的高颜值妹妹", CaptionParser.captionOf(topLevelCaption))
    }

    @Test
    fun `媒体对象内部 caption 能取到`() {
        assertEquals("组内描述", CaptionParser.captionOf(nestedCaption))
    }

    @Test
    fun `图片消息顶层 caption 也能取到`() {
        val photo = obj(
            """
            {"@type":"messagePhoto",
             "photo":{"sizes":[]},
             "caption":{"text":"图集说明","entities":[]}}
            """.trimIndent(),
        )
        assertEquals("图集说明", CaptionParser.captionOf(photo))
    }

    @Test
    fun `没有 caption 返回 null`() {
        val video = obj("""{"@type":"messageVideo","video":{"duration":1}}""")
        assertNull(CaptionParser.captionOf(video))
    }

    @Test
    fun `空白 caption 视为没有描述`() {
        val video = obj("""{"@type":"messageVideo","video":{},"caption":{"text":"   "}}""")
        assertNull(CaptionParser.captionOf(video))
    }

    @Test
    fun `非媒体消息不抛异常且返回 null`() {
        assertNull(CaptionParser.captionOf(obj("""{"@type":"messageText","text":{"text":"hi"}}""")))
        assertNull(CaptionParser.captionOf(null))
    }

    /** 只有顶层有、内部没有：确保不是被位置 2 的 null 提前 return 掉。 */
    @Test
    fun `顶层优先于内部`() {
        val both = obj(
            """
            {"@type":"messageVideo",
             "video":{"caption":{"text":"内部"}},
             "caption":{"text":"顶层"}}
            """.trimIndent(),
        )
        assertEquals("顶层", CaptionParser.captionOf(both))
    }
}
