package com.yg.tdrive.data

/** Three columns show more posters at once, so keep their decoration cheap. */
object PosterRenderPolicy {
    data class Decoration(
        val drawGradient: Boolean,
        val showDuration: Boolean,
        val showDetails: Boolean,
    ) {
        companion object {
            val REGULAR = Decoration(drawGradient = true, showDuration = true, showDetails = true)
            val COMPACT = Decoration(drawGradient = false, showDuration = true, showDetails = true)
        }
    }

    fun decoration(columns: Int): Decoration =
        if (columns >= 3) Decoration.COMPACT else Decoration.REGULAR
}
