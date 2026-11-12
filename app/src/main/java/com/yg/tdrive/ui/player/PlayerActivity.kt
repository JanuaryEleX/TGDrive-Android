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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.data.MediaEntity
import com.yg.tgdrive.data.PlaybackProgressEntity
import com.yg.tgdrive.download.FaststartRemuxer
import com.yg.tgdrive.download.PlaybackDiag
import com.yg.tgdrive.ui.common.AppIcons
import com.yg.tgdrive.download.TdDataSource
import com.yg.tgdrive.download.TdDataSourceFactory
import com.yg.tgdrive.download.TdVirtualDataSourceFactory
import com.yg.tgdrive.ui.common.AppTheme
import com.yg.tgdrive.ui.common.formatDuration
import com.yg.tgdrive.ui.common.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放页（P0 体验重做）。
 *
 * - 时间轴：控制层收起时底部常驻 2dp 细进度条；点屏展开完整控制层（可拖 Slider）
 * - 倍速：6 档 + 全局记忆（SettingsStore.playerSpeed），长按临时 2x 松手回原速
 * - 填充：适应/裁切/拉伸/原始 四档循环 + 全局记忆（SettingsStore.playerFill）
 * - 自动隐藏：播放中 3s 收起；暂停/出错常显
 * - 锁屏：锁定后所有控件（含细条）隐藏，只留解锁钮
 * - 元信息：标题 + 分辨率 + 大小 + 非默认状态标签
 * - 首帧：准备中显示提示，超 10s 可取消返回
 * - 错误：自动退避重试 2 次 → 中文原因 + 手动重试
 * - 上下集：按发起播放时的列表（queue）顺序手动切，绝不自动连播
 * - 熄屏/切后台即暂停（不做后台播放，不引 MediaSession）
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        internal const val TAG = "PlayerActivity"
        internal const val EXTRA_MEDIA_ID = "mediaId"
        internal const val EXTRA_QUEUE = "queue"
        internal const val EXTRA_INDEX = "index"
        internal const val RESUME_MIN_MS = 30_000L
        internal const val FINISHED_RATIO = 0.95
        internal const val SEEK_SWEEP_SECONDS = 15f
        internal const val DOUBLE_TAP_STEP_MS = 10_000L
        internal const val CONTROLS_HIDE_MS = 3_000L
        internal const val PROGRESS_TICK_MS = 5_000L
        internal const val UI_TICK_MS = 400L
        internal const val MAX_AUTO_RETRY = 2
        internal const val SLOW_PREPARE_MS = 10_000L
        internal val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        internal val FILL_LABELS = arrayOf("适应", "裁切", "拉伸", "原始")
    }

    private var player: ExoPlayer? = null
    private var mediaId = 0L
    private var item: MediaEntity? = null
    private var audioManager: AudioManager? = null
    private var maxVolume = 1

    /** 全局记忆的倍速（长按 2x 不写入） */
    private var baseSpeed = 1f
    private var speedBoosted = false
    private var fillMode = 0
    private var autoRetry = 0

    /**
     * 重试时要恢复到的位置。播放中断后 player 已被 release，currentPosition 归零，
     * 所以必须在报错前把位置抓下来，否则重试会从头 prepare（用户看到"跳回开头"）。
     */
    private var retryResumeMs = 0L
    private var preparing = false
    private var queue: LongArray = LongArray(0)
    private var queueIndex = -1

    internal val title = mutableStateOf("")
    internal val metaLine = mutableStateOf("")
    internal val stateTag = mutableStateOf("")
    internal val errorText = mutableStateOf<String?>(null)
    internal val loading = mutableStateOf(true)
    internal val prepared = mutableStateOf(false)
    internal val buffering = mutableStateOf(false)
    internal val playing = mutableStateOf(false)
    internal val positionMs = mutableStateOf(0L)
    internal val durationMs = mutableStateOf(0L)
    internal val controlsVisible = mutableStateOf(true)
    internal val locked = mutableStateOf(false)
    internal val seekPreview = mutableStateOf<String?>(null)
    internal val hudText = mutableStateOf<String?>(null)
    internal val speed = mutableStateOf(1f)
    internal val fill = mutableStateOf(0)
    internal val slowPrepare = mutableStateOf(false)
    internal val hasPrev = mutableStateOf(false)
    internal val hasNext = mutableStateOf(false)
    internal val queueLabel = mutableStateOf("")
    internal val currentFileId = mutableStateOf(0)
    internal val bufferedFraction = mutableStateOf(0f)
    internal val isLocalSource = mutableStateOf(false)

    /** 大文件未做 faststart 重排（moov 可能在尾部）：错误覆盖层给「离线下载」入口。 */
    internal val needsFullDownload = mutableStateOf(false)

    private var controlsHideJob: Job? = null
    private var hudClearJob: Job? = null
    private var slowPrepareJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = handlePlaybackError(error)

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> buffering.value = true
                Player.STATE_READY -> {
                    buffering.value = false
                    prepared.value = true
                    loading.value = false
                    autoRetry = 0
                    preparing = false
                    slowPrepare.value = false
                    slowPrepareJob?.cancel()
                    player?.setPlaybackSpeed(baseSpeed)
                }
                Player.STATE_ENDED -> {
                    buffering.value = false
                    lifecycleScope.launch { persistProgress(finished = true) }
                    pokeControls(true)
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing.value = isPlaying
            if (isPlaying) scheduleHide() else pokeControls(true)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speed.value = playbackParameters.speed
            refreshTag()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1L)
        queue = intent.getLongArrayExtra(EXTRA_QUEUE) ?: LongArray(0)
        queueIndex = intent.getIntExtra(EXTRA_INDEX, -1)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1

        val p = ExoPlayer.Builder(this).build().also { it.addListener(playerListener) }
        player = p

        lifecycleScope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                if (p.isPlaying) persistProgress()
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(UI_TICK_MS)
                val dur = p.duration
                durationMs.value = if (dur > 0) dur else 0L
                positionMs.value = p.currentPosition.coerceAtLeast(0L)
                // 缓存进度 = 已落盘字节 / 总字节（整文件本地直连时=100%）。
                // 无会话（本地直连/虚拟重排早期）时退回 ExoPlayer bufferedPosition。
                val fid = currentFileId.value
                val total = if (fid > 0) ServiceLocator.downloadManager.totalBytes(fid) else 0L
                val done = if (fid > 0) ServiceLocator.downloadManager.bufferedBytes(fid) else 0L
                val durF = if (dur > 0) dur.toFloat() else 1f
                bufferedFraction.value = when {
                    isLocalSource.value -> 1f
                    total > 0L -> (done.toFloat() / total).coerceIn(0f, 1f)
                    else -> (p.bufferedPosition.coerceAtLeast(p.currentPosition).toFloat() / durF).coerceIn(0f, 1f)
                }
            }
        }
        lifecycleScope.launch {
            baseSpeed = runCatching { ServiceLocator.settings.playerSpeed.first() }.getOrDefault(1f)
            fillMode = runCatching { ServiceLocator.settings.playerFill.first() }.getOrDefault(0)
            speed.value = baseSpeed
            fill.value = fillMode
            p.setPlaybackSpeed(baseSpeed)
            refreshTag()
            preparePlayer(initial = true)
        }

        setContent {
            AppTheme {
                PlayerScreen(activity = this, player = p)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 熄屏 / 切后台即暂停（设计决策：不做后台播放）
        player?.pause()
        // 退后台立刻停掉在飞下载，避免"退出后仍在下载"（onDestroy 再兜底硬取消一次）
        ServiceLocator.downloadManager.cancelAll()
        lifecycleScope.launch { persistProgress() }
        dumpDiag()
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceLocator.downloadManager.cancelAll()
        player?.release()
        player = null
    }

    // ═══════════════════════ 装配 ═══════════════════════

    private suspend fun preparePlayer(initial: Boolean) {
        val dao = ServiceLocator.appDatabase.mediaDao()
        val entity = item ?: dao.getById(mediaId)?.also { item = it }
        if (entity == null) {
            errorText.value = "媒体不存在 (id=$mediaId)"
            loading.value = false
            return
        }
        title.value = entity.displayName
        metaLine.value = buildMeta(entity)
        errorText.value = null
        prepared.value = false
        buffering.value = true
        loading.value = true
        preparing = true
        slowPrepare.value = false
        slowPrepareJob?.cancel()
        slowPrepareJob = lifecycleScope.launch {
            delay(SLOW_PREPARE_MS)
            if (preparing) slowPrepare.value = true
        }
        refreshQueueState()

        val fileId = ServiceLocator.mediaRepository.freshFileId(entity)
        currentFileId.value = if (fileId > 0) fileId else 0
        if (fileId <= 0) {
            preparing = false
            errorText.value = "file_id 已失效且重取失败，请刷新媒体库后重试"
            loading.value = false
            buffering.value = false
            return
        }
        val p = player ?: return

        val prepareT0 = android.os.SystemClock.elapsedRealtime()
        PlaybackDiag.begin(mediaId, fileId, entity.size)
        needsFullDownload.value = false

        // ① 本地完整文件直连：app LRU 缓存 / TDLib 已完整下载的最终文件。
        //    完整文件本地随机读无需 faststart 重排（moov-at-tail 也能按绝对偏移正确 seek），
        //    零 TDLib 下载、零 prepare 远距离读。
        val localCache = entity.fileUniqueId?.takeIf { it.isNotBlank() }
            ?.let { ServiceLocator.downloadManager.cacheDirFor(it) }
            ?: ServiceLocator.downloadManager.completedLocalFile(fileId)
        isLocalSource.value = localCache != null && localCache.length() > 0L
        if (localCache != null && localCache.length() > 0L) {
            PlaybackDiag.source("local")
            PlaybackDiag.prepareDone(android.os.SystemClock.elapsedRealtime() - prepareT0, false)
            p.setMediaSource(
                ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(this))
                    .createMediaSource(
                        MediaItem.fromUri(android.net.Uri.fromFile(localCache))
                    )
            )
        } else {
            val mediaItem = MediaItem.Builder()
                .setUri(TdDataSource.buildUri(fileId, entity.size, entity.fileUniqueId))
                .build()
            // moov 在文件尾的 MP4：先做 faststart 虚拟重排（IO 线程），否则 Mp4Extractor
            // 会 seek 到尾部读 moov 并把采样偏移二次叠加 → 越界 EOF。
            // build 内部有硬上限（小文件 15s / 大文件 60s），超时或链式解析失败才返回 null → TdDataSource 直连。
            val plan = if (entity.size > 0L) {
                withContext(Dispatchers.IO) {
                    FaststartRemuxer.build(ServiceLocator.downloadManager, fileId, entity.size)
                }
            } else {
                null
            }
            PlaybackDiag.prepareDone(
                android.os.SystemClock.elapsedRealtime() - prepareT0,
                PlaybackDiag.wasPrepareTimedOut(),
            )
            val factory: androidx.media3.datasource.DataSource.Factory = if (plan != null) {
                PlaybackDiag.source("td_virtual")
                TdVirtualDataSourceFactory(ServiceLocator.downloadManager, plan)
            } else {
                PlaybackDiag.source("td_data")
                // 极少数重排失败的超大文件（链断裂）才提示完整下载；
                // 常规大文件已能重排，不该再弹这个提示。
                needsFullDownload.value =
                    entity.size > FaststartRemuxer.FASTSTART_MAX_BYTES &&
                    entity.mime?.contains("mp4", ignoreCase = true) != false
                TdDataSourceFactory(ServiceLocator.downloadManager)
            }
            p.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem))
        }

        var resumeMs = 0L
        if (initial) {
            val prog = ServiceLocator.playbackProgressDao.get(mediaId)
            if (prog != null && !prog.finished && prog.positionMs > RESUME_MIN_MS) {
                resumeMs = prog.positionMs
            }
        } else if (retryResumeMs > 0L) {
            // 自动重试：从断点续播，不跳回开头
            resumeMs = retryResumeMs
        }
        if (resumeMs > 0) {
            p.seekTo(resumeMs)
            Toast.makeText(this, "从 ${formatDuration(resumeMs)} 继续播放", Toast.LENGTH_SHORT).show()
        }
        p.setPlaybackSpeed(baseSpeed)
        p.prepare()
        p.playWhenReady = true
        loading.value = false
    }

    private fun buildMeta(entity: MediaEntity): String {
        val parts = mutableListOf<String>()
        val w = entity.width ?: 0
        val h = entity.height ?: 0
        if (w > 0 && h > 0) parts += "${minOf(w, h)}p"
        if (entity.size > 0) parts += formatSize(entity.size)
        val d = entity.durationMs ?: 0L
        if (d > 0) parts += formatDuration(d)
        return parts.joinToString(" · ")
    }

    private fun refreshTag() {
        val parts = mutableListOf<String>()
        if (abs(speed.value - 1f) > 0.01f) parts += trimSpeed(speed.value)
        if (fill.value != 0) parts += FILL_LABELS[fill.value.coerceIn(0, 3)]
        stateTag.value = parts.joinToString(" · ")
    }

    private fun trimSpeed(v: Float): String =
        if (kotlin.math.abs(v - v.toInt().toFloat()) < 0.01f) "${v.toInt()}x"
        else "${v}x"

    private fun refreshQueueState() {
        hasPrev.value = queue.isNotEmpty() && queueIndex > 0 && queueIndex < queue.size
        hasNext.value = queue.isNotEmpty() && queueIndex >= 0 && queueIndex < queue.size - 1
        queueLabel.value = if (queue.isNotEmpty() && queueIndex in queue.indices) {
            "${queueIndex + 1}/${queue.size}"
        } else ""
    }

    // ═══════════════════════ 上下集 ═══════════════════════

    fun playPrev() = switchTo(queueIndex - 1)

    fun playNext() = switchTo(queueIndex + 1)

    private fun switchTo(index: Int) {
        if (index < 0 || index >= queue.size) return
        queueIndex = index
        val target = queue[index]
        if (target == mediaId) return
        lifecycleScope.launch {
            persistProgress()
            player?.pause()
            item = null
            mediaId = target
            durationMs.value = 0L
            positionMs.value = 0L
            preparePlayer(initial = true)
        }
    }

    fun retry() {
        retryResumeMs = player?.currentPosition?.coerceAtLeast(0L) ?: retryResumeMs
        autoRetry = 0
        errorText.value = null
        loading.value = true
        lifecycleScope.launch { preparePlayer(initial = false) }
    }

    /** 大文件 moov-at-tail 首播失败时：把该媒体排进离线下载（整文件落盘后可播）。 */
    fun enqueueOffline() {
        val e = item ?: return
        ServiceLocator.offlineDownloads.enqueue(e)
        Toast.makeText(this, "已加入离线下载，完成后可播放", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 播放页收尾：落一份 play-diag.json（写失败不影响退出）。 */
    private fun dumpDiag() {
        // appScope：onStop→onDestroy 之间 lifecycleScope 会被取消，用应用级 scope 保证落盘。
        ServiceLocator.appScope.launch { PlaybackDiag.finish(applicationContext) }
    }

    private fun handlePlaybackError(error: PlaybackException) {
        buffering.value = false
        prepared.value = false
        preparing = false
        if (autoRetry < MAX_AUTO_RETRY) {
            autoRetry++
            // 抓当前位置：重试必须从断点继续，不能跳回开头
            retryResumeMs = player?.currentPosition?.coerceAtLeast(0L) ?: retryResumeMs
            hudText.value = "播放中断，正在重试 $autoRetry/$MAX_AUTO_RETRY"
            lifecycleScope.launch {
                delay(1_200L * autoRetry)
                preparePlayer(initial = false)
            }
            return
        }
        errorText.value = describeError(error)
        loading.value = false
        pokeControls(true)
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

    // ═══════════════════════ 控件 / 锁定 ═══════════════════════

    fun toggleControls() {
        if (locked.value) return
        pokeControls(!controlsVisible.value)
    }

    fun pokeControls(visible: Boolean) {
        controlsVisible.value = visible
        controlsHideJob?.cancel()
        if (visible && !locked.value && playing.value) scheduleHide()
    }

    private fun scheduleHide() {
        controlsHideJob?.cancel()
        if (locked.value) return
        controlsHideJob = lifecycleScope.launch {
            delay(CONTROLS_HIDE_MS)
            if (playing.value && !locked.value) controlsVisible.value = false
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

    // ═══════════════════════ 进度 / 播放控制 ═══════════════════════

    fun seekTo(ms: Long) {
        val dur = player?.duration ?: 0L
        if (dur <= 0) return
        player?.seekTo(ms.coerceIn(0L, dur))
        positionMs.value = ms.coerceIn(0L, dur)
        pokeControls(true)
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        pokeControls(true)
    }

    fun pickSpeed(value: Float) {
        baseSpeed = value
        speed.value = value
        player?.setPlaybackSpeed(value)
        refreshTag()
        lifecycleScope.launch { ServiceLocator.settings.setPlayerSpeed(value) }
        pokeControls(true)
    }

    fun cycleFill() {
        fillMode = (fillMode + 1) % FILL_LABELS.size
        fill.value = fillMode
        refreshTag()
        lifecycleScope.launch { ServiceLocator.settings.setPlayerFill(fillMode) }
        flashHud("画面 ${FILL_LABELS[fillMode]}")
        pokeControls(true)
    }

    // ═══════════════════════ 手势 ═══════════════════════

    fun onDoubleTap(leftHalf: Boolean) {
        val p = player ?: return
        val dur = p.duration
        if (dur <= 0) return
        val target = (p.currentPosition + if (leftHalf) -DOUBLE_TAP_STEP_MS else DOUBLE_TAP_STEP_MS)
            .coerceIn(0L, dur)
        p.seekTo(target)
        positionMs.value = target
        flashHud(if (leftHalf) "快退 10 秒" else "快进 10 秒")
    }

    fun onSeekDragStart(): Long = player?.currentPosition ?: 0L

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
        positionMs.value = targetMs
    }

    fun cancelDragOverlays() {
        seekPreview.value = null
        hudText.value = null
    }

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
        speedBoosted = true
        p.setPlaybackSpeed(2f)
        flashHud("2x 快进中")
    }

    fun onLongPressEnd() {
        if (!speedBoosted) return
        speedBoosted = false
        player?.setPlaybackSpeed(baseSpeed)
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
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "网络中断了，检查网络或代理后重试"
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            "数据读取失败：该位置片段可能还没落盘，重试通常会恢复"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
            "文件位置越界，可能是文件已被删除或换源"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "文件封装异常（moov 损坏或不是有效 MP4）"
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "这个编码本机硬解不支持，可转 MP4(H.264+AAC) 后重传"
        else -> "播放失败：${e.errorCodeName}（已自动重试 $MAX_AUTO_RETRY 次）"
    }
}

// ═══════════════════════ Compose UI ═══════════════════════

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
    val metaLine by activity.metaLine
    val stateTag by activity.stateTag
    val errorText by activity.errorText
    val prepared by activity.prepared
    val buffering by activity.buffering
    val playing by activity.playing
    val positionMs by activity.positionMs
    val durationMs by activity.durationMs
    val bufferedFraction by activity.bufferedFraction
    val seekPreview by activity.seekPreview
    val hudText by activity.hudText
    val speed by activity.speed
    val fill by activity.fill
    val slowPrepare by activity.slowPrepare
    val needsFullDownload by activity.needsFullDownload
    val hasPrev by activity.hasPrev
    val hasNext by activity.hasNext
    val queueLabel by activity.queueLabel
    val view = LocalView.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    DisposableEffect(Unit) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 播放器是独立 Activity：主页的状态栏设置不会继承到这里。
        // 保持系统状态栏可见，布局由系统处理顶端 inset，避免内容压在状态栏下面。
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowCompat.getInsetsController(window, view)
        controller.show(WindowInsetsCompat.Type.statusBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { v ->
                v.player = player
                v.resizeMode = when (fill) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层（未锁定时生效）
        if (!locked) {
            val dragState = remember { DragState() }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(player) {
                        detectTapGestures(
                            onTap = { activity.toggleControls() },
                            onDoubleTap = { offset -> activity.onDoubleTap(offset.x < size.width / 2f) },
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
                                    null -> Unit
                                }
                            },
                            onDragEnd = {
                                val st = dragState
                                when (st.horizontal) {
                                    true -> activity.onSeekDragEnd(st.targetMs)
                                    false -> activity.onVerticalDragEnd()
                                    null -> Unit
                                }
                            },
                            onDragCancel = { activity.cancelDragOverlays() },
                        )
                    }
            )
        }

        seekPreview?.let { CenterOverlay(it) }
        hudText?.let { CenterOverlay(it) }

        // 常驻细进度条（控制层收起且已有进度时）
        if (!locked && !controlsVisible && prepared && durationMs > 0) {
            val frac = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.18f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        if (controlsVisible && !locked) {
            TopBar(
                title = title,
                meta = metaLine,
                tag = stateTag,
                onBack = { activity.finish() },
                onLock = { activity.toggleLock() },
                isFullscreen = isLandscape,
                onToggleFullscreen = { toggleFullscreen(activity) },
                modifier = Modifier.align(Alignment.TopStart),
            )
            BottomBar(
                activity = activity,
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                buffered = bufferedFraction,
                speed = speed,
                fill = fill,
                hasPrev = hasPrev,
                hasNext = hasNext,
                queueLabel = queueLabel,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

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

        // 准备中（首帧之前）
        if (!prepared && errorText == null) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(14.dp))
                    Text("正在准备播放流…", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (slowPrepare) "该视频信息在文件尾部，需要先拉取末段" else "首次打开需要拉取索引与首段",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                    )
                    if (slowPrepare) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = { activity.finish() }) {
                            Text("取消返回", color = Color.White)
                        }
                    }
                }
            }
        }

        // 缓冲中（已就绪后卡顿）
        if (prepared && buffering && errorText == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            }
        }

        errorText?.let { msg ->
            Box(
                Modifier.fillMaxSize().background(Color(0xE6000000)),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (needsFullDownload) {
                            TextButton(onClick = { activity.enqueueOffline() }) {
                                Text("离线下载后播放", color = Color.White)
                            }
                        }
                        TextButton(onClick = { activity.retry() }) {
                            Text("重试", color = Color.White)
                        }
                        TextButton(onClick = { activity.finish() }) {
                            Text("返回", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 全屏切换：竖屏 -> 横屏全屏，横屏 -> 回竖屏。
 * Manifest 锁定 portrait（始终竖屏），只有点「全屏」按钮才会临时横屏。
 */
private fun toggleFullscreen(activity: PlayerActivity) {
    val landscape =
        activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    activity.requestedOrientation = if (landscape) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}

@Composable
private fun TopBar(
    title: String,
    meta: String,
    tag: String,
    onBack: () -> Unit,
    onLock: () -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0x99000000))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = listOf(meta, tag).filter { it.isNotBlank() }.joinToString("  ")
                if (line.isNotBlank()) {
                    Text(
                        line,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    if (isFullscreen) AppIcons.FullscreenExit else AppIcons.Fullscreen,
                    contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onLock) {
                LockIcon(locked = false, tint = Color.White)
            }
        }
    }
}

/**
 * 播放进度条（自绘）：同一条轨道内三层——已播放(主色) / 已缓存(白色半透明) / 未下载(灰)。
 * 支持点按与拖动 seek。
 */
@Composable
private fun PlayerSeekBar(
    value: Float,
    buffered: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    var drag by remember { mutableStateOf<Float?>(null) }
    val pos = drag ?: value
    Box(
        modifier
            .height(30.dp)
            .semantics { contentDescription = "播放进度${(pos * 100).toInt()}%，缓存${(buffered * 100).toInt()}%" }
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    onSeek((off.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off -> drag = (off.x / size.width.toFloat()).coerceIn(0f, 1f) },
                    onDrag = { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        drag?.let { onSeek(it) }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val radius = size.height / 2f
            drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = CornerRadius(radius, radius))
            val bw = size.width * buffered.coerceIn(0f, 1f)
            if (bw > 0f) {
                drawRoundRect(
                    Color.White.copy(alpha = 0.45f),
                    size = Size(bw, size.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            val pw = size.width * pos.coerceIn(0f, 1f)
            if (pw > 0f) {
                drawRoundRect(
                    primary,
                    size = Size(pw, size.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val r = 7.dp.toPx()
            val cx = (size.width * pos.coerceIn(0f, 1f)).coerceIn(r, (size.width - r).coerceAtLeast(r))
            drawCircle(primary, radius = r, center = Offset(cx, size.height / 2f))
        }
    }
}

@Composable
private fun BottomBar(
    activity: PlayerActivity,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    buffered: Float,
    speed: Float,
    fill: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    queueLabel: String,
    modifier: Modifier = Modifier,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    val durF = durationMs.coerceAtLeast(1L).toFloat()

    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x000A0D12), Color(0xCC0A0D12), Color(0xF20A0D12))
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDuration(positionMs),
                color = Color.White,
                fontSize = 12.sp,
            )
            PlayerSeekBar(
                value = (positionMs.toFloat() / durF).coerceIn(0f, 1f),
                buffered = buffered,
                onSeek = { f -> activity.seekTo((f * durF).toLong()) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                formatDuration(durationMs),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { activity.playPrev() }, enabled = hasPrev) {
                    Icon(
                        AppIcons.SkipPrevious,
                        contentDescription = "上一集",
                        tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(26.dp),
                    )
                }
                Box(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { activity.togglePlayPause() }) {
                        Icon(
                            if (playing) AppIcons.Pause else AppIcons.Play,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                IconButton(onClick = { activity.playNext() }, enabled = hasNext) {
                    Icon(
                        AppIcons.SkipNext,
                        contentDescription = "下一集",
                        tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(26.dp),
                    )
                }
                if (queueLabel.isNotBlank()) {
                    Text(queueLabel, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { toggleFullscreen(activity) }) {
                    Text(
                        if (activity.resources.configuration.orientation ==
                            Configuration.ORIENTATION_LANDSCAPE
                        ) "退出全屏" else "全屏",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
                Box {
                    TextButton(onClick = { speedMenu = true }) {
                        Text(
                            if (abs(speed - 1f) < 0.01f) "倍速" else trimSpeedLabel(speed),
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        PlayerActivity.SPEEDS.forEach { v ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (abs(v - 1f) < 0.01f) "1x 正常" else trimSpeedLabel(v),
                                        fontWeight = if (abs(v - speed) < 0.01f) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    activity.pickSpeed(v)
                                    speedMenu = false
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { activity.cycleFill() }) {
                    Text(
                        PlayerActivity.FILL_LABELS[fill.coerceIn(0, 3)],
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

private fun trimSpeedLabel(v: Float): String =
    if (abs(v - v.toInt().toFloat()) < 0.01f) "${v.toInt()}x" else "${v}x"

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
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.22f, h * 0.46f),
            size = Size(w * 0.56f, h * 0.40f),
            cornerRadius = CornerRadius(w * 0.08f),
        )
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
