package com.yg.tgdrive.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 筛选/排序状态（全局搜索页、视频栏、图片栏共用）。
 *
 * 约定：
 * - kind: null=全部, "video", "image"
 * - minSizeMb/maxSizeMb: 以 MB 为单位的整数区间
 * - minDurationMin/maxDurationMin: 以「分钟」为单位的时长区间（仅视频有效）
 * - dateFromMs/dateToMs: epoch **毫秒**，dateToMs 已含当天末尾
 * - sortBy: name | date | size | duration；desc=true 降序
 */
data class FilterState(
    val kind: String? = null,
    val minSizeMb: Long? = null,
    val maxSizeMb: Long? = null,
    val minDurationMin: Long? = null,
    val maxDurationMin: Long? = null,
    val dateFromMs: Long? = null,
    val dateToMs: Long? = null,
    val sortBy: String = "date",
    val desc: Boolean = true,
) {
    /** 是否有任何生效的筛选/非默认排序。 */
    fun isActive(): Boolean =
        kind != null ||
            minSizeMb != null || maxSizeMb != null ||
            minDurationMin != null || maxDurationMin != null ||
            dateFromMs != null || dateToMs != null ||
            sortBy != "date" || !desc

    val minSizeBytes: Long? get() = minSizeMb?.let { it * 1024L * 1024L }
    val maxSizeBytes: Long? get() = maxSizeMb?.let { it * 1024L * 1024L }
    val minDurationMs: Long? get() = minDurationMin?.let { it * 60_000L }
    val maxDurationMs: Long? get() = maxDurationMin?.let { it * 60_000L }
    val dateFromSec: Long? get() = dateFromMs?.let { it / 1000L }
    val dateToSec: Long? get() = dateToMs?.let { it / 1000L }
}

/** 顶部/工具区「筛选」小胶囊按钮：active 时用主色高亮并带一个小圆点。 */
@Composable
fun FilterButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) accent.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "筛选",
            style = MaterialTheme.typography.labelMedium,
            color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (active) {
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

/**
 * 共用底部筛选面板。
 * @param initial 打开时的初始状态（通常传当前 FilterState）
 * @param onDismiss 关闭
 * @param onApply 点「应用」时回调新的 FilterState
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    initial: FilterState,
    onDismiss: () -> Unit,
    onApply: (FilterState) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var kind by remember { mutableStateOf(initial.kind) }
    var minSize by remember { mutableStateOf(initial.minSizeMb?.toString() ?: "") }
    var maxSize by remember { mutableStateOf(initial.maxSizeMb?.toString() ?: "") }
    var minDuration by remember { mutableStateOf(initial.minDurationMin?.toString() ?: "") }
    var maxDuration by remember { mutableStateOf(initial.maxDurationMin?.toString() ?: "") }
    var dateFrom by remember { mutableStateOf(formatDateInput(initial.dateFromMs)) }
    var dateTo by remember { mutableStateOf(formatDateInput(initial.dateToMs)) }
    var sortBy by remember { mutableStateOf(initial.sortBy) }
    var desc by remember { mutableStateOf(initial.desc) }

    fun resetLocal() {
        kind = null
        minSize = ""
        maxSize = ""
        minDuration = ""
        maxDuration = ""
        dateFrom = ""
        dateTo = ""
        sortBy = "date"
        desc = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text(
                "筛选",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SectionLabel("类型")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipItem("全部", kind == null) { kind = null }
                FilterChipItem("视频", kind == "video") { kind = "video" }
                FilterChipItem("图片", kind == "image") { kind = "image" }
            }

            SectionLabel("大小（MB）")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumField(minSize, { minSize = it }, "最小", Modifier.weight(1f))
                Text("~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumField(maxSize, { maxSize = it }, "最大", Modifier.weight(1f))
            }

            SectionLabel("时长（分钟，仅视频）")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NumField(minDuration, { minDuration = it }, "最短", Modifier.weight(1f))
                Text("~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumField(maxDuration, { maxDuration = it }, "最长", Modifier.weight(1f))
            }

            SectionLabel("日期范围（yyyy-MM-dd，可留空）")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DateField(dateFrom, { dateFrom = it }, "起始", Modifier.weight(1f))
                Text("~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                DateField(dateTo, { dateTo = it }, "结束", Modifier.weight(1f))
            }

            SectionLabel("排序")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipItem("名称", sortBy == "name") { sortBy = "name" }
                FilterChipItem("日期", sortBy == "date") { sortBy = "date" }
                FilterChipItem("大小", sortBy == "size") { sortBy = "size" }
                FilterChipItem("时长", sortBy == "duration") { sortBy = "duration" }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChipItem("降序", desc) { desc = true }
                FilterChipItem("升序", !desc) { desc = false }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = { resetLocal() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("重置")
                }
                Button(
                    onClick = {
                        onApply(
                            FilterState(
                                kind = kind,
                                minSizeMb = minSize.toLongOrNull(),
                                maxSizeMb = maxSize.toLongOrNull(),
                                minDurationMin = minDuration.toLongOrNull(),
                                maxDurationMin = maxDuration.toLongOrNull(),
                                dateFromMs = parseDateMs(dateFrom),
                                // 结束日期含当天末尾
                                dateToMs = parseDateMs(dateTo)?.let { it + 86_399_000L },
                                sortBy = sortBy,
                                desc = desc,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("应用")
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FilterChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) accent.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NumField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun DateField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '-' }.take(10)) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

private fun parseDateMs(text: String): Long? {
    val t = text.trim()
    if (t.length != 10) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(t)?.time
    }.getOrNull()
}

private fun formatDateInput(ms: Long?): String {
    if (ms == null || ms <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
}
