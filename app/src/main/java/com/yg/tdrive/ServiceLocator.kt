package com.yg.tgdrive

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.yg.tgdrive.data.AppDatabase
import com.yg.tgdrive.data.DriveManager
import com.yg.tgdrive.data.MediaRepository
import com.yg.tgdrive.data.PlaybackProgressDao
import com.yg.tgdrive.download.StreamingDownloadManager
import com.yg.tgdrive.settings.CredentialsStore
import com.yg.tgdrive.settings.SettingsStore
import com.yg.tgdrive.td.TdClient
import com.yg.tgdrive.td.TdProxyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // ---- 下载层 ----
    lateinit var downloadManager: StreamingDownloadManager
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

            appDatabase = Room.databaseBuilder(app, AppDatabase::class.java, DB_NAME).build()
            // 三参构造：appContext 供缩略图落盘（cacheDir/thumbs）与 clearCache 使用
            mediaRepository = MediaRepository(appDatabase, tdClient, app)
            driveManager = DriveManager(appDatabase, tdClient)
            playbackProgressDao = appDatabase.playbackProgressDao()

            // 下载层：并发 Flow 由 manager 内部 collect 实现 1~8 滑杆热更（SPEC §4.3 Q28）
            downloadManager = StreamingDownloadManager(
                td = tdClient,
                concurrency = settings.concurrency,
                cacheDir = app.cacheDir
            )
            // 设置页"清空缓存"：缩略图目录由 MediaRepository 清，LRU 缓存经钩子委托下载层
            mediaRepository.clearCacheHook = { downloadManager.clearCache() }
            // 设置页"重扫"：DriveManager 桥接到 MediaRepository.incrementalSync
            // 设置页"重扫"走全量：增量会被 last_scanned 幂等短路，无法修复空库
            driveManager.rescanBridge = { driveId ->
                appScope.launch { mediaRepository.fullScan(driveId) }
            }

            initialized = true
        }
    }

    /** 便捷判空（UI 冷启动竞态防御用，正常流程不需要）。 */
    val isInitialized: Boolean get() = initialized
}
