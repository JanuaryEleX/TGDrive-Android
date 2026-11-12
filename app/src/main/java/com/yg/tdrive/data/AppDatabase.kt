package com.yg.tgdrive.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 索引库（SPEC §5）。version=4（v3→v4 追加 media.favorited_at 收藏列），
 * schema 导出关闭（gradle room.schemaLocation 未配置，
 * 与之保持一致显式 exportSchema=false）。
 * media_fts 为 external content FTS4：@Fts4(contentEntity=MediaEntity) 由 Room 在建库时
 * 自动创建虚表 `media_fts USING fts4(display_name, content=media, content_rowid=id)`
 * 及全部同步触发器，无需手写 trigger。
 */
@Database(
    entities = [
        DriveEntity::class,
        MediaEntity::class,
        MediaFts::class,
        PlaybackProgressEntity::class,
        DownloadTask::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun driveDao(): DriveDao
    abstract fun mediaDao(): MediaDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        const val NAME = "tgdrive.db"

        /**
         * v1 → v2：media 增加 media_album_id（TG 相册 ID）。
         * 只加可空列，不改既有列/索引；老行初始为 NULL，由后台回填任务补齐。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN media_album_id INTEGER")
            }
        }

        /**
         * v2 → v3：新增 download_task（离线下载任务表）。
         * 与 data/DownloadTask.kt 实体字段一一对应；不触碰既有表。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_task` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `media_id` INTEGER NOT NULL,
                        `file_id` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `target_path` TEXT,
                        `state` INTEGER NOT NULL,
                        `bytes_done` INTEGER NOT NULL,
                        `bytes_total` INTEGER NOT NULL,
                        `priority` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `error_msg` TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 → v4：media 增加 favorited_at（收藏时间，epoch ms，可空 = 未收藏）+ 索引。
         *
         * 只追加一列与一个索引，不改既有列/约束，也不重建表 —— 老库里的收藏状态
         * 初始为 NULL（= 全部未收藏），媒体行本身不受影响。
         * 索引名与 Entities.kt 里 @Index(name = "idx_media_favorited_at") 必须**完全一致**，
         * 否则 Room 启动时的 identity hash / 结构校验会判定迁移不完整。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN favorited_at INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_media_favorited_at` ON `media` (`favorited_at`)"
                )
            }
        }

        /** ServiceLocator 之外的便捷入口（测试/预览用；生产接线走 ServiceLocator）。 */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
