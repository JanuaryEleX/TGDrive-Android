package com.yg.tgdrive.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun driveDao(): DriveDao
    abstract fun mediaDao(): MediaDao
    abstract fun playbackProgressDao(): PlaybackProgressDao

    companion object {
        const val NAME = "tgdrive.db"

        /** ServiceLocator 之外的便捷入口（测试/预览用；生产接线走 ServiceLocator）。 */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .build()
    }
}
