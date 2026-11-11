package com.yg.tgdrive.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yg.tgdrive.BuildConfig

/**
 * api_id / api_hash 加密存储。
 *
 * 凭据来源按构建类型区分：
 * - debug：自定义优先，未设置时回退内置开发凭据（开发者本机零配置登录）；
 * - release：不内置任何凭据，未设置时 [getOrBuiltIn] 返回 null，必须先由用户填写。
 *
 * 不在 release 内置的原因：内置值属于开发者在 my.telegram.org 注册的个人 app，
 * 多人共用会摊薄该 api_id 的登录配额（限流维度为 api_id+号码），且违反
 * Telegram「One api_id per account」，有封号风险。
 */
class CredentialsStore(context: Context) {

    companion object {
        /**
         * 开发用 api 凭据（my.telegram.org 注册的 app，属主账号私有）。
         * 仅 debug 构建读取；release 构建经 R8 死代码消除后不进入 dex。
         */
        const val BUILT_IN_API_ID = 0
        const val BUILT_IN_API_HASH = "REDACTED"

    }

    /**
     * 内置凭据只对 debug 生效。`BuildConfig.DEBUG` 是**编译期常量**：
     * release 下该分支折叠为 null，两个字面量失去引用，被 R8 一并删除、不进 dex。
     * （不要改成运行时判断如 FLAG_DEBUGGABLE——那样常量有运行时引用，字面量会留在包里。）
     */
    private val builtIn: Pair<Int, String>? =
        if (BuildConfig.DEBUG) (BUILT_IN_API_ID to BUILT_IN_API_HASH) else null

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

    /** 登录用凭据：自定义优先，否则 debug 内置值；release 未配置时为 null。 */
    fun getOrBuiltIn(): Pair<Int, String>? = get() ?: builtIn

    /** 当前构建是否携带内置凭据（设置页据此决定是否展示「恢复内置」）。 */
    fun builtInCreds(): Pair<Int, String>? = builtIn

    fun set(apiId: Int, apiHash: String) {
        prefs.edit()
            .putInt("api_id", apiId)
            .putString("api_hash", apiHash)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
