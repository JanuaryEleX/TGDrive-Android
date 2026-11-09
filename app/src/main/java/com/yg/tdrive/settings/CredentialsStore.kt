package com.yg.tgdrive.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * api_id / api_hash 加密存储。
 * 默认内置 Telegram Desktop 官方开源仓库公开的凭据（第三方客户端通用做法），
 * 用户零填写即可登录；高级用户仍可在引导/设置中写入自定义凭据覆盖内置值。
 */
class CredentialsStore(context: Context) {

    companion object {
        /** Telegram Desktop（tdesktop 开源仓库）公开 api 凭据。 */
        const val BUILT_IN_API_ID = 0
        const val BUILT_IN_API_HASH = "REDACTED"
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "tgdrive_credentials", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** 仅读取用户自定义凭据；未设置返回 null。 */
    fun get(): Pair<Int, String>? {
        val id = prefs.getInt("api_id", 0)
        val hash = prefs.getString("api_hash", null) ?: return null
        if (id == 0 || hash.isBlank()) return null
        return id to hash
    }

    /** 登录用凭据：自定义优先，否则内置默认（免填登录）。 */
    fun getOrBuiltIn(): Pair<Int, String> = get() ?: (BUILT_IN_API_ID to BUILT_IN_API_HASH)

    fun set(apiId: Int, apiHash: String) {
        prefs.edit()
            .putInt("api_id", apiId)
            .putString("api_hash", apiHash)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
