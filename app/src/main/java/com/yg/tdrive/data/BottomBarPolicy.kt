package com.yg.tgdrive.data

/**
 * 底栏入口与筛选面板的对应关系。
 *
 * 最近(0)/收藏(1) 没有可筛选的条件，长按与点击一致；
 * 视频(2)/图片(3) 长按打开该页筛选面板。
 */
object BottomBarPolicy {
    const val RECENT = 0
    const val FAVORITES = 1
    const val VIDEOS = 2
    const val IMAGES = 3

    /** 该入口长按是否能打开筛选页。 */
    fun supportsFilter(tab: Int): Boolean = tab == VIDEOS || tab == IMAGES
}
