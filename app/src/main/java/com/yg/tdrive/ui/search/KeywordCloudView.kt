package com.yg.tgdrive.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.yg.tgdrive.data.KeywordCloud
import com.yg.tgdrive.data.WordCloudGeometry

/**
 * 词云：字号随出现次数变化，从中心螺旋排布，互不重叠、不出画布。
 * 全部词一次测量 + 一次 Canvas 绘制，点击按矩形命中。
 */
@Composable
fun KeywordCloudView(
    words: List<KeywordCloud.Word>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (words.isEmpty()) return

    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme) {
        listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primary, scheme.onSurfaceVariant, scheme.tertiary)
    }
    val maxCount = words.maxOf { it.count }.coerceAtLeast(1)
    val minCount = words.minOf { it.count }
    val span = (maxCount - minCount).coerceAtLeast(1)

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val canvasW = with(density) { maxWidth.toPx() }
        val canvasH = with(density) { maxHeight.toPx() }

        val scene = remember(words, canvasW, canvasH, palette) {
            val measured = words.mapIndexed { index, word ->
                val t = (word.count - minCount).toFloat() / span
                val layout = measurer.measure(
                    text = word.text,
                    style = TextStyle(
                        fontSize = (15f + 25f * t).sp,
                        fontWeight = if (t >= 0.5f) FontWeight.SemiBold else FontWeight.Normal,
                        color = palette[index % palette.size],
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                Triple(word.text, layout, index)
            }
            val placed = WordCloudGeometry.layout(
                words = measured.map { (text, layout, _) ->
                    WordCloudGeometry.Word(text, layout.size.width.toFloat(), layout.size.height.toFloat())
                },
                canvasWidth = canvasW,
                canvasHeight = canvasH,
            )
            val byText = measured.associate { it.first to it.second }
            placed.map { p -> Cell(p, byText.getValue(p.text)) }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(scene) {
                    detectTapGestures { offset ->
                        scene.lastOrNull { cell ->
                            offset.x >= cell.placed.left && offset.x <= cell.placed.right &&
                                offset.y >= cell.placed.top && offset.y <= cell.placed.bottom
                        }?.let { onSelect(it.placed.text) }
                    }
                },
        ) {
            scene.forEach { cell ->
                drawText(
                    textLayoutResult = cell.layout,
                    topLeft = Offset(cell.placed.left, cell.placed.top),
                )
            }
        }
    }
}

private class Cell(val placed: WordCloudGeometry.Placed, val layout: TextLayoutResult)
