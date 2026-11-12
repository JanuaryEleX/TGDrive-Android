package com.yg.tgdrive.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 收藏仓库（v3→v4 新增）。
 *
 * 刻意独立于 MediaRepository：收藏是纯本地状态（media.favorited_at 一列），
 * 不需要 TDLib、不需要缩略图、不需要扫描，混进 MediaRepository 只会让那个 1400 行的
 * 大文件继续膨胀，并且和并行修改它的分支打架。
 *
 * 所有写操作只改 favorited_at 一列：
 * - 取消收藏 → 置 NULL，媒体行本身保留（收藏页靠 Room 的 Flow/Paging 失效自动移除该格，
 *   UI 不需要手动刷新）；
 * - 收藏 → 写入当前时间戳，收藏页按它倒序。
 *
 * 收藏是**持久化到 DB 的**（按行 id 落在 media.favorited_at 上），不是内存态：
 * 进程被杀、App 重启、频道重扫、增量 upsert（走 copy 保留该列）都不会丢。
 * 注意：本 App 的 AndroidManifest 目前是 android:allowBackup="false"，因此「先卸载再安装」
 * 会随 App 数据目录一起被系统清除 —— 那是卸载语义，不是收藏没持久化。
 */
class FavoritesRepository(
    db: AppDatabase,
    private val dao: MediaDao = db.mediaDao(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 收藏页数据源：视频 + 图片混排，收藏时间倒序（Paging3）。
     * 页大小与海报墙一致（60/预取 30），复用同一套 PosterCell 网格。
     */
    fun observeFavorites(): Flow<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false),
            pagingSourceFactory = { dao.observeFavoritesPaged() },
        ).flow

    /**
     * 读某行当前收藏时间（null = 未收藏）。
     *
     * 详情弹层打开时用它覆盖网格里的旧快照 —— 网格是分页快照，用户可能在别处
     * 刚取消过收藏；「是/否」的判定交给 FavoritesPolicy，这里只给原始时间戳。
     */
    suspend fun favoritedAt(mediaId: Long): Long? =
        withContext(Dispatchers.IO) { dao.favoritedAtOf(mediaId) }

    /**
     * 切换收藏状态，返回**切换后**的值（null = 已取消收藏，正数 = 收藏时间）。
     *
     * 返回值给 UI 直接当作本地状态用：底部弹层点完立刻把按钮从「收藏」翻成「已收藏」，
     * 不必再查库。写库本身会触发 PagingSource 失效 → 收藏页自动增删该格。
     */
    suspend fun toggle(mediaId: Long): Long? = withContext(Dispatchers.IO) {
        val current = dao.favoritedAtOf(mediaId)
        val next = FavoritesPolicy.toggleValue(current, now())
        dao.setFavoritedAt(mediaId, next)
        next
    }

    /**
     * 收藏播放队列：点某个收藏视频时的上下集顺序（收藏时间倒序，最多 400 条）。
     * 队列来自全量收藏行，而不是「分页已加载的几十条」，所以滚到很下面点开也不会缺集。
     */
    suspend fun favoriteQueue(anchorId: Long): FavoriteQueue = withContext(Dispatchers.IO) {
        FavoritesPolicy.queue(dao.favoriteRows(), anchorId)
    }
}
