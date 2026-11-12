package com.yg.tdrive.ui.wall

/**
 * 海报格解码尺寸。
 *
 * 为什么需要它：Coil 若不指定 size，会按缩略图原始分辨率解码（常 1000px+）。
 * 三列时每格实际只有 ~110dp 宽，按原图解码等于白付 4 倍内存和一次大 Bitmap 上传，
 * 滚动时表现为 UI 线程持续掉帧（实测三列中位帧耗时是两列的两倍多）。
 *
 * 这里按「屏幕宽 - 左右 padding - 格间距」算出真实渲染像素，再交给 Coil。
 * 纯函数，便于单测覆盖列数与密度变化。
 */
object PosterSizing {

    /** 网格左右 contentPadding 之和（见 WallTab 的 16.dp × 2）。 */
    private const val H_PADDING_DP = 32

    /** 与 WallTab 的 horizontalArrangement 保持一致。 */
    private const val SPACING_TWO_COL_DP = 12
    private const val SPACING_THREE_COL_DP = 9

    /** 上限防止平板/极宽屏解出过大 Bitmap；下限防止小屏解出糊图。 */
    private const val MIN_PX = 180
    private const val MAX_PX = 640

    fun decodePx(screenWidthDp: Int, columns: Int, density: Float): Int {
        val cols = columns.coerceIn(1, 6)
        val spacing = if (cols >= 3) SPACING_THREE_COL_DP else SPACING_TWO_COL_DP
        val cellDp = (screenWidthDp - H_PADDING_DP - spacing * (cols - 1)) / cols
        val safeDensity = if (density <= 0f) 1f else density
        return (cellDp * safeDensity).toInt().coerceIn(MIN_PX, MAX_PX)
    }
}
