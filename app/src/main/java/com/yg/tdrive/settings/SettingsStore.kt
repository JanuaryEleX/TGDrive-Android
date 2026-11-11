package com.yg.tgdrive.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import com.yg.tgdrive.td.ProxyConfig

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 应用偏好（SPEC §5 末段）：明文项存 DataStore，api 凭据存 EncryptedSharedPreferences。
 */
class SettingsStore(private val context: Context) {

    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val concurrency = intPreferencesKey("download_concurrency")
        val cacheCapGb = intPreferencesKey("cache_cap_gb")
        val proxyKind = stringPreferencesKey("proxy_kind")           // none|mtproto|socks5|system
        val proxyServer = stringPreferencesKey("proxy_server")
        val proxyPort = intPreferencesKey("proxy_port")
        val proxySecret = stringPreferencesKey("proxy_secret")
        val proxyUser = stringPreferencesKey("proxy_user")
        val proxyPass = stringPreferencesKey("proxy_pass")
        val shareDefaultDrive = stringPreferencesKey("share_default_drive")
        val playerSpeed = floatPreferencesKey("player_speed")
        val playerFill = intPreferencesKey("player_fill")
        val librarySort = stringPreferencesKey("library_sort")
        val librarySortDesc = booleanPreferencesKey("library_sort_desc")
        val themeMode = stringPreferencesKey("theme_mode")          // system|light|dark
        val downloadStoreMode = stringPreferencesKey("download_store_mode") // private|public
    }

    // ---- 明文 ----

    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[K.onboarded] ?: false }
    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[K.onboarded] = v }

    /** 并发 1~8，默认 4（Q28）。 */
    val concurrency: Flow<Int> = context.dataStore.data.map {
        (it[K.concurrency] ?: 4).coerceIn(1, 8)
    }
    suspend fun setConcurrency(v: Int) =
        context.dataStore.edit { it[K.concurrency] = v.coerceIn(1, 8) }

    /** LRU 缓存上限 GB，1~64，默认 4（Q24 可调滑杆）。 */
    val cacheCapGb: Flow<Int> = context.dataStore.data.map {
        (it[K.cacheCapGb] ?: 4).coerceIn(1, 64)
    }
    suspend fun setCacheCapGb(v: Int) =
        context.dataStore.edit { it[K.cacheCapGb] = v.coerceIn(1, 64) }

    val shareDefaultDrive: Flow<String?> =
        context.dataStore.data.map { it[K.shareDefaultDrive] }
    suspend fun setShareDefaultDrive(chatId: String) =
        context.dataStore.edit { it[K.shareDefaultDrive] = chatId }

    // ---- 播放器 / 列表偏好（P0：倍速与填充全局记忆；P1：排序全局记忆） ----

    /** 播放倍速，0.5~2.0，默认 1.0（长按临时 2x 不写入这里）。 */
    val playerSpeed: Flow<Float> = context.dataStore.data.map {
        (it[K.playerSpeed] ?: 1f).coerceIn(0.5f, 2f)
    }
    suspend fun setPlayerSpeed(v: Float) =
        context.dataStore.edit { it[K.playerSpeed] = v.coerceIn(0.5f, 2f) }

    /** 画面填充：0 适应 / 1 裁切 / 2 拉伸 / 3 原始，默认 0。 */
    val playerFill: Flow<Int> = context.dataStore.data.map {
        (it[K.playerFill] ?: 0).coerceIn(0, 3)
    }
    suspend fun setPlayerFill(v: Int) =
        context.dataStore.edit { it[K.playerFill] = v.coerceIn(0, 3) }

    /** 列表排序维度：date|duration|size|name，默认 date。 */
    val librarySort: Flow<String> = context.dataStore.data.map {
        (it[K.librarySort] ?: "date").takeIf { s -> s in setOf("date", "duration", "size", "name") } ?: "date"
    }
    suspend fun setLibrarySort(v: String) =
        context.dataStore.edit { it[K.librarySort] = v }

    /** 排序方向：true = 倒序，默认 true。 */
    val librarySortDesc: Flow<Boolean> = context.dataStore.data.map {
        it[K.librarySortDesc] ?: true
    }
    suspend fun setLibrarySortDesc(v: Boolean) =
        context.dataStore.edit { it[K.librarySortDesc] = v }

    /** 主题模式：system|light|dark，默认跟随系统。 */
    val themeMode: Flow<String> = context.dataStore.data.map {
        (it[K.themeMode] ?: "system").takeIf { s -> s in setOf("system", "light", "dark") } ?: "system"
    }
    suspend fun setThemeMode(v: String) =
        context.dataStore.edit { it[K.themeMode] = v }

    /** 离线下载存储位置：private（应用私有目录）| public（公共下载目录）。 */
    val downloadStoreMode: Flow<String> = context.dataStore.data.map {
        (it[K.downloadStoreMode] ?: "private")
            .takeIf { m -> m in setOf("private", "public") } ?: "private"
    }
    suspend fun setDownloadStoreMode(v: String) =
        context.dataStore.edit { it[K.downloadStoreMode] = v }

    // ---- 代理 ----

    val proxy: Flow<ProxySnapshot?> = context.dataStore.data.map { p ->
        when (p[K.proxyKind]) {
            "mtproto" -> ProxySnapshot.Mt(
                p[K.proxyServer] ?: return@map null,
                p[K.proxyPort] ?: 443,
                p[K.proxySecret] ?: ""
            )
            "socks5" -> ProxySnapshot.Socks(
                p[K.proxyServer] ?: return@map null,
                p[K.proxyPort] ?: 1080,
                p[K.proxyUser], p[K.proxyPass]
            )
            "system" -> ProxySnapshot.System
            else -> null
        }
    }

    suspend fun setProxy(snapshot: ProxySnapshot?) {
        context.dataStore.edit { p ->
            if (snapshot == null) {
                p[K.proxyKind] = "none"
            } else when (snapshot) {
                is ProxySnapshot.Mt -> {
                    p[K.proxyKind] = "mtproto"
                    p[K.proxyServer] = snapshot.server
                    p[K.proxyPort] = snapshot.port
                    p[K.proxySecret] = snapshot.secret
                }
                is ProxySnapshot.Socks -> {
                    p[K.proxyKind] = "socks5"
                    p[K.proxyServer] = snapshot.server
                    p[K.proxyPort] = snapshot.port
                    p[K.proxyUser] = snapshot.user ?: ""
                    p[K.proxyPass] = snapshot.pass ?: ""
                }
                is ProxySnapshot.System -> {
                    p[K.proxyKind] = "system"
                    // 系统代理无静态地址：清掉旧配置残留，避免下次切回手填模式时回填脏值
                    p.remove(K.proxyServer)
                    p.remove(K.proxyPort)
                    p.remove(K.proxySecret)
                    p.remove(K.proxyUser)
                    p.remove(K.proxyPass)
                }
            }
        }
    }
}

sealed interface ProxySnapshot {
    data class Mt(val server: String, val port: Int, val secret: String) : ProxySnapshot
    data class Socks(val server: String, val port: Int, val user: String?, val pass: String?) : ProxySnapshot

    /** 「跟随系统代理」：真实配置在运行时由 [com.yg.tgdrive.td.SystemProxyResolver] 探测。 */
    data object System : ProxySnapshot

    /**
     * 无法在此解析 [System]（需要 Context 才能读 ConnectivityManager），故返回 null。
     * 冷启动/登录链路一律走 [com.yg.tgdrive.td.TdProxyManager.applySnapshot]，
     * 由它把 System 解析成 [ProxyConfig.Http]，探测失败时退化为直连。
     */
    fun toConfig(): ProxyConfig? = when (this) {
        is Mt -> ProxyConfig.MtProto(server, port, secret)
        is Socks -> ProxyConfig.Socks5(server, port, user, pass)
        is System -> null
    }

    fun toJson(): String = Json.encodeToString(
        kotlinx.serialization.serializer(),
        mapOf(
            "kind" to when (this) {
                is Mt -> "mtproto"
                is Socks -> "socks5"
                is System -> "system"
            }
        )
    )
}
