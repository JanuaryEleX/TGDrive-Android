package com.yg.tgdrive

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Room
import com.yg.tgdrive.data.AppDatabase
import com.yg.tgdrive.data.DriveManager
import com.yg.tgdrive.data.FavoritesRepository
import com.yg.tgdrive.data.MediaRepository
import com.yg.tgdrive.data.PlaybackProgressDao
import com.yg.tgdrive.download.OfflineDownloadManager
import com.yg.tgdrive.download.StreamingDownloadManager
import com.yg.tgdrive.settings.CredentialsStore
import com.yg.tgdrive.settings.SettingsStore
import com.yg.tgdrive.td.TdClient
import com.yg.tgdrive.td.TdProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 手写 DI 接线中心（SPEC §3：不上 Hilt）。
 *
 * 由 `TgDriveApp.onCreate()` 调用 `ServiceLocator.init(this)` 一次完成全部单例构建；
 * 任何成员在 init 前访问会抛 UninitializedPropertyAccessException（有意为之，尽早暴露）。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 【对并行 agent 的签名假设清单 —— lead 集成时按实际产出微调】
 *
 * TD 层（已存在，已按实际文件核对）:
 *   com.yg.tgdrive.td.TdClient()                          — 无参构造（实际代码与 SPEC §6 的 TdClient(scope) 不同，以文件为准）
 *   com.yg.tgdrive.td.TdProxyManager(td: TdClient)
 *
 * settings（已存在，已按实际文件核对）:
 *   com.yg.tgdrive.settings.SettingsStore(context: Context)   — concurrency: Flow<Int>（1..8 默认 4）
 *   com.yg.tgdrive.settings.CredentialsStore(context: Context)
 *
 * data 包（数据层 agent 并行在写，按 SPEC §5/§6 假设）:
 *   com.yg.tgdrive.data.AppDatabase                       — @Database 抽象类；
 *                                                           DAO 访问器名假设 playbackProgressDao(): PlaybackProgressDao
 *   com.yg.tgdrive.data.MediaRepository(db: AppDatabase, td: TdClient)      [SPEC §6 原文]
 *   com.yg.tgdrive.data.DriveManager(db: AppDatabase, td: TdClient)         — SPEC 未给 ctor，按 MediaRepository 同款两参假设；
 *                                                             需要第三个 scope 参数时由 lead 补
 *   com.yg.tgdrive.data.DriveItem                         — 至少含 id: Long / chatId: Long / title: String
 *   com.yg.tgdrive.data.PlaybackProgressDao               — @Dao 空参访问器
 *
 * download 包（下载层 agent 并行在写，按本文件约定假设；与 SPEC §6 的 (td, scope, settings)
 * 不同 —— 以本接线为准，lead 集成时对齐任一侧）:
 *   com.yg.tgdrive.download.StreamingDownloadManager(
 *       td: TdClient,
 *       concurrency: Flow<Int>,      // = settings.concurrency；manager 内部 collect 并热更自己的 setConcurrency
 *       cacheDir: File               // = context.cacheDir；内部派生 partial/ LRU/ thumbs/ 子目录
 *   )
 *   期望公开: setConcurrency(n: Int) / ensureRange(fileId, offset, len): PartialRef
 *            / cancelPending(fileId) / cancelAll() / cacheDirFor(fileUniqueId): File
 * ═══════════════════════════════════════════════════════════════════════════
 */
object ServiceLocator {

    /** Application context（init 后可用；禁止传给需要 Activity 场景的 API）。 */
    lateinit var context: Context
        private set

    /** 全应用共享协程域（M4 上传通知条、扫描调度等长生命周期任务用它）。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 下载进行中/待查看徽标数（Wave1-B 离线下载器更新；>0 时顶栏下载图标显示红点）。 */
    val downloadBadge = MutableStateFlow(0)

    // ---- TD 层 ----
    lateinit var tdClient: TdClient
        private set
    lateinit var proxyManager: TdProxyManager
        private set

    // ---- 偏好/凭据 ----
    lateinit var settings: SettingsStore
        private set
    lateinit var credentials: CredentialsStore
        private set

    // ---- 数据层（Room + Repository）----
    lateinit var appDatabase: AppDatabase
        private set
    lateinit var mediaRepository: MediaRepository
        private set
    lateinit var driveManager: DriveManager
        private set
    lateinit var playbackProgressDao: PlaybackProgressDao
        private set

    /** 收藏仓库（v3→v4：media.favorited_at，收藏 tab 唯一数据来源）。 */
    lateinit var favoritesRepository: FavoritesRepository
        private set

    // ---- 下载层 ----
    lateinit var downloadManager: StreamingDownloadManager
        private set

    /** 离线下载管理器（Wave1-B：整文件永久保存）。 */
    lateinit var offlineDownloads: OfflineDownloadManager
        private set

    private const val DB_NAME = "tgdrive.db"

    /** TDLib 数据库目录（tdlib/db）。 */
    val tdDatabaseDir: String
        get() = java.io.File(context.filesDir, "tdlib/db").apply { mkdirs() }.absolutePath

    /** TDLib 下载文件目录（tdlib/files）。 */
    val tdFilesDir: String
        get() = java.io.File(context.filesDir, "tdlib/files").apply { mkdirs() }.absolutePath

    @Volatile
    private var initialized = false

    /**
     * 构建全部单例。在 `TgDriveApp.onCreate()` 调用一次；重复调用抛异常。
     * 注意：这里不做任何 TDLib 网络调用 —— TdClient 构造只起 receive 循环，
     * 代理应用（proxyManager.apply）由 Onboarding/设置页在用户操作时执行。
     */
    fun init(app: Application) {
        synchronized(this) {
            check(!initialized) { "ServiceLocator.init 已被调用，禁止重复初始化" }
            context = app

            tdClient = TdClient()
            settings = SettingsStore(app)
            credentials = CredentialsStore(app)
            proxyManager = TdProxyManager(tdClient)

            appDatabase = Room.databaseBuilder(app, AppDatabase::class.java, DB_NAME)
                .addMigrations(
                    AppDatabase.MIGRATION_1_2,
                    AppDatabase.MIGRATION_2_3,
                    AppDatabase.MIGRATION_3_4,
                )
                .build()
            // 三参构造：appContext 供缩略图落盘（cacheDir/thumbs）与 clearCache 使用
            mediaRepository = MediaRepository(appDatabase, tdClient, app)
            driveManager = DriveManager(appDatabase, tdClient)
            playbackProgressDao = appDatabase.playbackProgressDao()
            favoritesRepository = FavoritesRepository(appDatabase)

            // 下载层：并发 Flow 由 manager 内部 collect 实现 1~8 滑杆热更（SPEC §4.3 Q28）
            downloadManager = StreamingDownloadManager(
                td = tdClient,
                concurrency = settings.concurrency,
                cacheDir = app.cacheDir
            )
            // 设置页缓存上限滑杆（1~64GB）热更到 LRU 逐出阈值。
            // 此前只写偏好、从不读取下载层，用户把上限调到 64GB 仍按常量 4GB 逐出。
            appScope.launch {
                settings.cacheCapGb.collect { gb -> downloadManager.setCacheCapGb(gb.toLong()) }
            }
            // 设置页"清空缓存"：缩略图目录由 MediaRepository 清，LRU 缓存经钩子委托下载层
            mediaRepository.clearCacheHook = { downloadManager.clearCache() }
            // 设置页"重扫"：DriveManager 桥接到 MediaRepository.incrementalSync
            // 设置页"重扫"走全量：增量会被 last_scanned 幂等短路，无法修复空库
            driveManager.rescanBridge = { driveId ->
                appScope.launch { mediaRepository.fullScan(driveId) }
            }

            // 离线下载管理：整文件永久保存 + 断点续传（appScope 承载长任务）
            offlineDownloads = OfflineDownloadManager(
                context = app,
                td = tdClient,
                dao = appDatabase.downloadTaskDao(),
                settings = settings,
                scope = appScope,
                badge = downloadBadge,
            )
            // App 重启后续传：回落「下载中」→「排队」并等 TDLib 就绪后重新调度
            offlineDownloads.reload()

            initialized = true
        }
    }

    /**
     * 相册 ID 回填（v1→v2）。由 TgDriveApp 在 TDLib 就绪（authState Ready）后调用，
     * 因为回填要 getChatHistory，未就绪/未应用代理时取不到历史。
     * 仅跑一次（maintenance 持久标记）；失败不标记，下次启动自动重试。
     */
    fun scheduleAlbumBackfill(app: Context) {
        val prefs = app.getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ALBUM_BACKFILL_DONE, false)) {
            scheduleCaptionRename(app, prefs)
            return
        }
        appScope.launch {
            val n = runCatching { mediaRepository.backfillAlbumIds() }.getOrNull()
            if (n != null) {
                prefs.edit().putBoolean(KEY_ALBUM_BACKFILL_DONE, true).apply()
                Log.i("ServiceLocator", "相册ID回填完成: $n 行")
                scheduleCaptionRename(app, prefs)
            } else {
                Log.w("ServiceLocator", "相册ID回填失败（下次启动重试）")
            }
        }
    }

    /**
     * 按组内文字描述重命名历史视频，仅跑一次。
     * 新包扫描只对新入库行生效，老行必须单独回填一次（见 MediaRepository.backfillAlbumCaptions）。
     */
    private fun scheduleCaptionRename(app: Context, prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_CAPTION_RENAME_DONE, false)) return
        appScope.launch {
            val n = runCatching { mediaRepository.backfillAlbumCaptions() }.getOrNull()
            if (n != null) {
                prefs.edit().putBoolean(KEY_CAPTION_RENAME_DONE, true).apply()
                Log.i("ServiceLocator", "描述改名回填完成: $n 行")
            } else {
                Log.w("ServiceLocator", "描述改名回填失败（下次启动重试）")
            }
        }
    }

    private const val KEY_CAPTION_RENAME_DONE = "album_caption_rename_v3"

    private const val KEY_ALBUM_BACKFILL_DONE = "album_backfill_done_v2"

    /** 便捷判空（UI 冷启动竞态防御用，正常流程不需要）。 */
    val isInitialized: Boolean get() = initialized
}
