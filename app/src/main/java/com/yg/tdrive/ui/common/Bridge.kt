package com.yg.tgdrive.ui.common

import com.yg.tgdrive.ServiceLocator
import com.yg.tgdrive.settings.CredentialsStore
import com.yg.tgdrive.settings.SettingsStore
import com.yg.tgdrive.td.TdClient
import com.yg.tgdrive.td.TdProxyManager

/**
 * UI 层到应用服务层的接线点与实体类型别名。
 * lead 若调整了 ServiceLocator 成员名或实体包名，只需改本文件。
 *
 * 【需要 lead 确认的 ServiceLocator 成员】
 * - tdClient: TdClient                     （已存在于 td 包，成员名按任务书）
 * - settings: SettingsStore                （settings 包已有）
 * - credentials: CredentialsStore          （settings 包已有）
 * - proxyManager: TdProxyManager           （td 包已有）
 * - mediaRepository: MediaRepository       （lead 提供，需有：
 *      observeWall(driveId: Long, kind: String): Flow<PagingData<MediaEntity>>
 *      observeContinueWatching(): Flow<List<MediaEntity>>
 *      search(q: String): List<MediaEntity>            (suspend)
 *      incrementalSync(driveId: Long)                  (suspend)
 *      delete(item: MediaEntity)                       (suspend)
 *      rename(item: MediaEntity, newName: String)      (suspend)
 *      resetProgress(mediaId: Long)                    (suspend)  ← 详情"重播"用，若命名不同请改 Bridge/UI
 *      clearCache()                                    (suspend)  ← 设置页"清空缓存"用
 * ）
 * - driveManager: DriveManager             （lead 提供，需有：
 *      observeDrives(): Flow<List<DriveEntity>>
 *      addDrive(chatId: Long, title: String)           (suspend，任务书指定签名)
 *      rescan(driveId: Long)                           (suspend)
 *      removeDrive(driveId: Long)                      (suspend)  ← 设置页删除驱动器用
 * ）
 * - data.MediaEntity / data.DriveEntity    （lead 的 Room 实体，字段见 SPEC §5；
 *   UI 期望属性名 camelCase：id, driveId, chatId, messageId, kind, displayName,
 *   size, durationMs, width, height, messageDate, thumbPath）
 */
object Graph {
    val td: TdClient get() = ServiceLocator.tdClient
    val settings: SettingsStore get() = ServiceLocator.settings
    val credentials: CredentialsStore get() = ServiceLocator.credentials
    val proxy: TdProxyManager get() = ServiceLocator.proxyManager
    val media get() = ServiceLocator.mediaRepository
    val drives get() = ServiceLocator.driveManager
}
