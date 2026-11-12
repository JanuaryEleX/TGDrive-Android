package com.yg.tgdrive.data

/**
 * 底栏页面的层序：页面首次进入后保持存活（不重建、不重新查库），
 * 非活动页留在下层但不可交互，活动页永远最后绘制（即最上层，先拿到点击）。
 */
object TabLayerPolicy {
    fun drawOrder(visited: List<Int>, active: Int): List<Int> {
        val inactive = visited.filter { it != active }
        return if (active in visited) inactive + active else inactive
    }

    fun isInteractive(tab: Int, active: Int): Boolean = tab == active
}
