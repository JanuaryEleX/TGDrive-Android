package com.yg.tgdrive.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekRelocationPolicyTest {

    private val mb = 1024L * 1024L

    /** 真机回归：moov 在尾的 2.4GB 文件，prepare 读过尾部 → 向前拖到中段必须重定位。 */
    @Test fun forwardDragOnMoovAtTailFileRelocates() {
        // base=0，TDLib 报回的前缀只有 7MB（顺序下到 7MB 处）
        assertTrue(SeekRelocationPolicy.needsRelocation(base = 0L, prefix = 7 * mb, target = 1500 * mb))
    }

    /** 连续播放：位置落在顺序前沿之后一点点 → 不该打断在飞请求。 */
    @Test fun sequentialPlaybackDoesNotRelocate() {
        assertFalse(SeekRelocationPolicy.needsRelocation(base = 0L, prefix = 64 * mb, target = 60 * mb))
    }

    /** 前视窗口内的小幅前跳（拖动几十秒）→ 等顺序下载即可。 */
    @Test fun smallForwardJumpWithinLookaheadDoesNotRelocate() {
        assertFalse(
            SeekRelocationPolicy.needsRelocation(
                base = 0L,
                prefix = 64 * mb,
                target = 64 * mb + SeekRelocationPolicy.LOOKAHEAD_BYTES - 1,
            )
        )
    }

    /** 刚超出前视窗口 → 重定位。 */
    @Test fun jumpBeyondLookaheadRelocates() {
        assertTrue(
            SeekRelocationPolicy.needsRelocation(
                base = 0L,
                prefix = 64 * mb,
                target = 64 * mb + SeekRelocationPolicy.LOOKAHEAD_BYTES + 1,
            )
        )
    }

    /** 往回拖 → 必须重定位。 */
    @Test fun backwardSeekRelocates() {
        assertTrue(SeekRelocationPolicy.needsRelocation(base = 500 * mb, prefix = 8 * mb, target = 100 * mb))
    }

    /** 重定位后 base 已在新位置 → 紧接着的读不重复重定位。 */
    @Test fun afterRelocationReadsAtNewBaseAreQuiet() {
        assertFalse(SeekRelocationPolicy.needsRelocation(base = 1500 * mb, prefix = 1 * mb, target = 1500 * mb))
        assertFalse(SeekRelocationPolicy.needsRelocation(base = 1500 * mb, prefix = 1 * mb, target = 1505 * mb))
    }

    /** 还没发过请求 → 必须发。 */
    @Test fun noRequestYetRelocates() {
        assertTrue(SeekRelocationPolicy.needsRelocation(base = -1L, prefix = 0L, target = 0L))
    }
}
