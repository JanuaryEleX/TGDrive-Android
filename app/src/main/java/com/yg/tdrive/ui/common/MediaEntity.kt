package com.yg.tgdrive.ui.common

/**
 * UI 层统一实体别名：全部指向 data.Room 实体（lead 的 data/Entities.kt）。
 * WallTab / GalleryTab / MediaDetailSheet / PlayerNav 等历史 UI 文件均按
 * `com.yg.tgdrive.ui.common.MediaEntity` 导入 —— 本文件补齐该符号。
 */
typealias MediaEntity = com.yg.tgdrive.data.MediaEntity

typealias DriveEntity = com.yg.tgdrive.data.DriveEntity
