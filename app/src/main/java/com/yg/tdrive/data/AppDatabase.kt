package com.yg.tgdrive.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 索引库（SPEC §5）。version=1，schema 导出关闭（gradle room.schemaLocation 未配置，
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
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun driveDao(): DriveDao
    abstract fun mediaDao(): MediaDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        const val NAME = "tgdrive.db"

        /** ServiceLocator 之外的便捷入口（测试/预览用；生产接线走 ServiceLocator）。 */
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
