package com.yg.tgdrive.data

/**
 * 底栏页面的层序：页面首次进入后保持存活（不重建、不重新查库），
 * 非活动页留在下层且不可交互，活动页始终在最上层。
 *
 * 层序只用 zIndex 表达，不重排组合顺序 —— 重排会让页面被当作新节点重建。
 */
object TabLayerPolicy {
    fun zIndex(tab: Int, active: Int): Float = if (tab == active) 1f else 0f

    fun isInteractive(tab: Int, active: Int): Boolean = tab == active
}
