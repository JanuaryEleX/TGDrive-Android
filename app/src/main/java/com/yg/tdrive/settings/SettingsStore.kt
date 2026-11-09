package com.yg.tgdrive.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
        val proxyKind = stringPreferencesKey("proxy_kind")           // none|mtproto|socks5
        val proxyServer = stringPreferencesKey("proxy_server")
        val proxyPort = intPreferencesKey("proxy_port")
        val proxySecret = stringPreferencesKey("proxy_secret")
        val proxyUser = stringPreferencesKey("proxy_user")
        val proxyPass = stringPreferencesKey("proxy_pass")
        val shareDefaultDrive = stringPreferencesKey("share_default_drive")
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
            }
        }
    }
}

sealed interface ProxySnapshot {
    data class Mt(val server: String, val port: Int, val secret: String) : ProxySnapshot
    data class Socks(val server: String, val port: Int, val user: String?, val pass: String?) : ProxySnapshot

    fun toConfig(): ProxyConfig = when (this) {
        is Mt -> ProxyConfig.MtProto(server, port, secret)
        is Socks -> ProxyConfig.Socks5(server, port, user, pass)
    }

    fun toJson(): String = Json.encodeToString(
        kotlinx.serialization.serializer(),
        mapOf("kind" to when (this) { is Mt -> "mtproto"; is Socks -> "socks5" })
    )
}
