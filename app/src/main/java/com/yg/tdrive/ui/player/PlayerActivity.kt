package com.yg.tgdrive.ui.player

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.data.MediaEntity
import com.yg.tgdrive.data.PlaybackProgressEntity
import com.yg.tgdrive.download.StreamingDownloadManager
import com.yg.tgdrive.download.TdDataSource
import com.yg.tgdrive.download.TdDataSourceFactory
import com.yg.tgdrive.ui.common.AppTheme
import com.yg.tgdrive.ui.common.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放页（SPEC §4.4 / 任务书 C）：
 * - Compose setContent + AndroidView(PlayerView)，全屏横屏（manifest: sensorLandscape + 黑底主题）
 * - mediaId（Intent extra "mediaId"）→ mediaDao.getById → freshFileId 重取 →
 *   ExoPlayer + ProgressiveMediaSource(TdDataSourceFactory)
 * - 续播：position > 30s 且未 finished → seekTo + Toast
 * - 手势包：左右横滑 ±15s（中央预览浮层）/ 左半竖滑亮度 / 右半竖滑音量 /
 *   双击左右半屏 ±10s / 长按 2x 松手恢复 / 锁屏按钮锁全部手势
 * - 进度每 5s 节流 + onPause + 播完(≥95%) → playbackProgressDao.upsert
 * - 错误覆盖层（文案 + 重试）；onDestroy: downloadManager.cancelAll + player.release()
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        internal const val TAG = "PlayerActivity"
        internal const val EXTRA_MEDIA_ID = "mediaId"
        internal const val RESUME_MIN_MS = 30_000L
        internal const val FINISHED_RATIO = 0.95
        internal const val SEEK_SWEEP_SECONDS = 15f   // 全屏宽横滑 = ±15s
        internal const val DOUBLE_TAP_STEP_MS = 10_000L
        internal const val CONTROLS_HIDE_MS = 3_500L
        internal const val PROGRESS_TICK_MS = 5_000L
    }

    private var player: ExoPlayer? = null
    private var mediaId = 0L
    private var item: MediaEntity? = null
    private var audioManager: AudioManager? = null
    private var maxVolume = 1
    private var savedSpeed = 1f
    private var speedBoosted = false

    // Compose 可观察状态（主线程读写；顶层 PlayerScreen composable 读取）
    internal val title = mutableStateOf("")
    internal val errorText = mutableStateOf<String?>(null)
    internal val loading = mutableStateOf(true)
    internal val controlsVisible = mutableStateOf(true)
    internal val locked = mutableStateOf(false)
    internal val seekPreview = mutableStateOf<String?>(null)
    internal val hudText = mutableStateOf<String?>(null)

    private var controlsHideJob: Job? = null
    private var hudClearJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            errorText.value = describeError(error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                lifecycleScope.launch { persistProgress(finished = true) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1L)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1

        val p = ExoPlayer.Builder(this).build().also { it.addListener(playerListener) }
        player = p

        // 每 5s 节流保存进度
        lifecycleScope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                if (p.isPlaying) persistProgress()
            }
        }

        setContent {
            AppTheme {
                PlayerScreen(activity = this, player = p)
            }
        }
        lifecycleScope.launch { preparePlayer(initial = true) }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch { persistProgress() }
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceLocator.downloadManager.cancelAll()
        player?.release()
        player = null
    }

    // ═══════════════════════ 装配 / 进度 ═══════════════════════

    private suspend fun preparePlayer(initial: Boolean) {
        val dao = ServiceLocator.appDatabase.mediaDao()
        val entity = item ?: dao.getById(mediaId)?.also { item = it }
        if (entity == null) {
            errorText.value = "媒体不存在 (id=$mediaId)"
            loading.value = false
            return
        }
        title.value = entity.displayName
        errorText.value = null
        val fileId = ServiceLocator.mediaRepository.freshFileId(entity)
        if (fileId <= 0) {
            errorText.value = "file_id 已失效且重取失败，请刷新媒体库后重试"
            loading.value = false
            return
        }
        val p = player ?: return
        val mediaItem = MediaItem.Builder()
            .setUri(TdDataSource.buildUri(fileId, entity.size, entity.fileUniqueId))
            .build()
        // moov 在文件尾的 MP4：先做 faststart 虚拟重排（IO 线程，避免主线程等待下载）。
        // 否则 Mp4Extractor 会 seek 到尾部读 moov，并把采样偏移按尾部基准二次叠加，
        // 得到 > 文件长度的位置后越界 EOF（Loading finished before preparation）。
        val plan = if (entity.size > 0L) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.yg.tgdrive.download.FaststartRemuxer.build(
                    ServiceLocator.downloadManager, fileId, entity.size
                )
            }
        } else {
            null
        }
        val factory: androidx.media3.datasource.DataSource.Factory = if (plan != null) {
            com.yg.tgdrive.download.TdVirtualDataSourceFactory(ServiceLocator.downloadManager, plan)
        } else {
            TdDataSourceFactory(ServiceLocator.downloadManager)
        }
        val source = ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
        p.setMediaSource(source)

        var resumeMs = 0L
        if (initial) {
            val prog = ServiceLocator.playbackProgressDao.get(mediaId)
            if (prog != null && !prog.finished && prog.positionMs > RESUME_MIN_MS) {
                resumeMs = prog.positionMs
            }
        }
        if (resumeMs > 0) {
            p.seekTo(resumeMs)
            Toast.makeText(this, "从 ${formatDuration(resumeMs)} 继续播放", Toast.LENGTH_SHORT).show()
        }
        p.prepare()
        p.playWhenReady = true
        loading.value = false
    }

    fun retry() {
        errorText.value = null
        loading.value = true
        lifecycleScope.launch { preparePlayer(initial = false) }
    }

    private suspend fun persistProgress(finished: Boolean = false) {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0) return
        val pos = p.currentPosition.coerceIn(0L, dur)
        val fin = finished || pos >= dur * FINISHED_RATIO
        withContext(Dispatchers.IO) {
            runCatching {
                ServiceLocator.playbackProgressDao.upsert(
                    PlaybackProgressEntity(
                        mediaId = mediaId,
                        positionMs = pos,
                        durationMs = dur,
                        finished = fin,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }.onFailure { Log.w(TAG, "persistProgress failed", it) }
        }
    }

    // ═══════════════════════ 控制条显隐 / 锁定 ═══════════════════════

    fun toggleControls() {
        if (locked.value) return
        pokeControls(!controlsVisible.value)
    }

    fun pokeControls(visible: Boolean) {
        controlsVisible.value = visible
        controlsHideJob?.cancel()
        if (visible && !locked.value) {
            controlsHideJob = lifecycleScope.launch {
                delay(CONTROLS_HIDE_MS)
                controlsVisible.value = false
            }
        }
    }

    fun toggleLock() {
        locked.value = !locked.value
        if (locked.value) {
            controlsVisible.value = false
            controlsHideJob?.cancel()
        } else {
            pokeControls(true)
        }
    }

    // ═══════════════════════ 手势回调（Compose 手势层调用，主线程） ═══════════════════════

    fun onDoubleTap(leftHalf: Boolean) {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0) return
        val target = (p.currentPosition + if (leftHalf) -DOUBLE_TAP_STEP_MS else DOUBLE_TAP_STEP_MS)
            .coerceIn(0L, dur)
        p.seekTo(target)
        flashHud(if (leftHalf) "快退 10 秒" else "快进 10 秒")
    }

    fun onSeekDragStart(): Long = player?.currentPosition ?: 0L

    /** 横滑：整屏宽 = ±15s；返回目标时间并更新中央预览浮层。 */
    fun onSeekDrag(baseMs: Long, deltaSeconds: Float, durationMs: Long): Long {
        val target = (baseMs + (deltaSeconds * 1000).toLong()).coerceIn(0L, durationMs)
        val diffSec = (target - baseMs) / 1000
        val sign = if (diffSec >= 0) "+" else ""
        seekPreview.value = String.format(Locale.US, "%s%ds → %s", sign, diffSec, formatDuration(target))
        return target
    }

    fun onSeekDragEnd(targetMs: Long) {
        seekPreview.value = null
        player?.seekTo(targetMs)
    }

    fun cancelDragOverlays() {
        seekPreview.value = null
        hudText.value = null
    }

    /** 竖滑起点值：左半屏=亮度基线（系统默认 -1 视为 0.5），右半屏=音量基线 0..1。 */
    fun onVerticalDragStart(leftHalf: Boolean): Float =
        if (leftHalf) {
            window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
        } else {
            (audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) / maxVolume.toFloat()
        }

    fun onVerticalDrag(leftHalf: Boolean, base: Float, deltaFraction: Float) {
        if (leftHalf) {
            val v = (base + deltaFraction).coerceIn(0.01f, 1f)
            window.attributes = window.attributes.also { it.screenBrightness = v }
            hudText.value = "亮度 ${(v * 100).toInt()}%"
        } else {
            val v = ((base + deltaFraction) * maxVolume).roundToInt().coerceIn(0, maxVolume)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            hudText.value = "音量 ${(v * 100 / maxVolume)}%"
        }
    }

    fun onVerticalDragEnd() {
        hudClearJob?.cancel()
        hudText.value = null
    }

    fun onLongPressStart() {
        val p = player ?: return
        if (speedBoosted) return
        savedSpeed = p.playbackParameters.speed
        p.setPlaybackSpeed(2f)
        speedBoosted = true
        flashHud("2x 快进中")
    }

    fun onLongPressEnd() {
        if (!speedBoosted) return
        speedBoosted = false
        player?.setPlaybackSpeed(savedSpeed)
        hudClearJob?.cancel()
        hudText.value = null
    }

    private fun flashHud(text: String) {
        hudText.value = text
        hudClearJob?.cancel()
        hudClearJob = lifecycleScope.launch {
            delay(800)
            hudText.value = null
        }
    }

    private fun describeError(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            "网络连接失败，请检查网络或代理设置"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
            "下载数据异常，请重试"
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "该编码 Media3 硬解不支持，建议转 MP4(H.264+AAC) 后重传"
        else -> "播放出错（${e.errorCodeName}），点击重试"
    }
}

// ═══════════════════════ Compose UI ═══════════════════════

/** 手势拖拽进行中的状态（纯可变对象，手势回调内读写，不参与重组）。 */
private class DragState {
    var horizontal: Boolean? = null
    var leftHalf = false
    var accumX = 0f
    var accumY = 0f
    var baseSeekMs = 0L
    var baseValue = 0f
    var targetMs = 0L
}

@Composable
private fun PlayerScreen(activity: PlayerActivity, player: ExoPlayer) {
    val locked by activity.locked
    val controlsVisible by activity.controlsVisible
    val title by activity.title
    val errorText by activity.errorText
    val loading by activity.loading
    val seekPreview by activity.seekPreview
    val hudText by activity.hudText
    val view = LocalView.current

    // 沉浸式全屏
    DisposableEffect(Unit) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply { useController = false }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层（未锁定时生效；锁定后仅解锁按钮可点）
        if (!locked) {
            val dragState = remember { DragState() }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(player) {
                        detectTapGestures(
                            onTap = { activity.toggleControls() },
                            onDoubleTap = { offset ->
                                activity.onDoubleTap(offset.x < size.width / 2f)
                            },
                            onLongPress = { activity.onLongPressStart() },
                            onPress = {
                                try {
                                    awaitRelease()
                                } finally {
                                    activity.onLongPressEnd()
                                }
                            },
                        )
                    }
                    .pointerInput(player) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragState.horizontal = null
                                dragState.leftHalf = offset.x < size.width / 2f
                                dragState.accumX = 0f
                                dragState.accumY = 0f
                                dragState.baseSeekMs = activity.onSeekDragStart()
                                dragState.baseValue = 0f
                                dragState.targetMs = 0L
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val st = dragState
                                st.accumX += amount.x
                                st.accumY += amount.y
                                if (st.horizontal == null &&
                                    (abs(st.accumX) > viewConfiguration.touchSlop ||
                                        abs(st.accumY) > viewConfiguration.touchSlop)
                                ) {
                                    st.horizontal = abs(st.accumX) >= abs(st.accumY)
                                    if (st.horizontal == false) {
                                        st.baseValue = activity.onVerticalDragStart(st.leftHalf)
                                    }
                                }
                                when (st.horizontal) {
                                    true -> {
                                        val dur = player.duration
                                        if (dur > 0) {
                                            val seconds = st.accumX / size.width * PlayerActivity.SEEK_SWEEP_SECONDS
                                            st.targetMs = activity.onSeekDrag(st.baseSeekMs, seconds, dur)
                                        }
                                    }
                                    false -> activity.onVerticalDrag(
                                        st.leftHalf,
                                        st.baseValue,
                                        -st.accumY / size.height,
                                    )
                                    null -> {}
                                }
                            },
                            onDragEnd = {
                                val st = dragState
                                when (st.horizontal) {
                                    true -> activity.onSeekDragEnd(st.targetMs)
                                    false -> activity.onVerticalDragEnd()
                                    null -> {}
                                }
                            },
                            onDragCancel = { activity.cancelDragOverlays() },
                        )
                    }
            )
        }

        // 中央预览浮层：横滑 seek 目标时间
        seekPreview?.let { text ->
            CenterOverlay(text)
        }
        // 中央 HUD：亮度/音量/双击/2x 提示
        hudText?.let { text ->
            CenterOverlay(text)
        }

        // 顶部：返回 + 标题 + 锁屏（自动隐藏）
        if (controlsVisible && !locked) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { activity.finish() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Text(
                    title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { activity.toggleLock() }) {
                    LockIcon(locked = false, tint = Color.White)
                }
            }
        }

        // 锁定态：仅保留解锁按钮
        if (locked) {
            IconButton(
                onClick = { activity.toggleLock() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                LockIcon(locked = true, tint = Color.White)
            }
        }

        // 加载中
        if (loading && errorText == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // 错误覆盖层：文案 + 重试
        errorText?.let { msg ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        msg,
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.Button(onClick = { activity.retry() }) {
                        Text("重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterOverlay(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color(0x88000000), MaterialTheme.shapes.large)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** 自绘锁图标（material-icons-core 不含 Lock，避免依赖 extended 包）。 */
@Composable
private fun LockIcon(locked: Boolean, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        // 锁体
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.22f, h * 0.46f),
            size = Size(w * 0.56f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.08f),
        )
        // 锁环（锁定 180°；解锁抬起 120°）
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = if (locked) 180f else 120f,
            useCenter = false,
            topLeft = Offset(w * 0.30f, h * 0.10f),
            size = Size(w * 0.40f, h * 0.42f),
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round),
        )
    }
}
