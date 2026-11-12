package com.yg.tgdrive.data

/**
 * 增量扫描的停止边界与检查点策略（纯逻辑，便于单元测试）。
 *
 * 约定：
 * - `lastScannedMessageId` 表示「**已索引区间的上界**」（top watermark）：
 *   id 大于它的消息才可能还没入库。下拉刷新只在这个上界之上找新内容。
 * - 历史深处是否扫全，另由 `scan_state` 表达：只有 DONE 才表示整段历史都走过了。
 *   ERROR / IDLE / SCANNING 都还需要一次显式全量扫描（设置页「重扫」）。
 *
 * 这样修掉的问题：曾经把「本轮到达的最老 id」写进检查点，导致下次下拉时
 * stopId 极低，增量退化成近乎全量的重扫。
 */
object IncrementalScanPolicy {

    /** 是否需要一次全量首扫/续扫（没有检查点，或历史没扫全）。 */
    fun needsFullScan(checkpoint: Long, scanState: String): Boolean =
        checkpoint <= 0L || scanState != ScanState.DONE

    /** 增量成功后把上界推进到当前最新消息；拿不到最新 id 时保持原值。 */
    fun checkpointAfterSuccess(previous: Long, newest: Long): Long =
        if (newest > previous) newest else previous

    /** 失败时保持既有上界，绝不回退（回退会让下次下拉重复走老消息）。 */
    fun checkpointAfterFailure(previous: Long, topOfScannedRange: Long): Long =
        previous

    /** 本页是否已经碰到检查点（说明新消息已扫完，可以停止）。 */
    fun reachedOldMessages(ids: List<Long>, stopId: Long): Boolean =
        stopId > 0L && ids.any { it <= stopId }

    /** 本页是否已经碰到库里已有的消息（用户要求：从最新往下扫，扫到旧的为止）。 */
    fun reachedKnownMessages(ids: List<Long>, knownIds: Set<Long>): Boolean =
        ids.any { it in knownIds }
}
