package com.yg.tdrive.data

/**
 * 三列同时可见的海报数量约为两列的两倍，逐格装饰必须更省，
 * 否则滚动帧耗时随格子数线性上涨（实测三列 p90 ≈ 两列 2 倍）。
 */
object PosterRenderPolicy {
    data class Decoration(
        /** 是否绘制托住时长胶囊的底部渐变（每格一层渐变 = 每帧多一次绘制）。 */
        val drawGradient: Boolean,
    ) {
        companion object {
            val REGULAR = Decoration(drawGradient = true)
            val COMPACT = Decoration(drawGradient = false)
        }
    }

    fun decoration(columns: Int): Decoration =
        if (columns >= 3) Decoration.COMPACT else Decoration.REGULAR
}
