package com.yg.tgdrive.td

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * td_json_client C 接口的 JNA 绑定。
 * 对应 libtdjson.so（up9cloud/android-libtdjson v1.8.65, arm64-v8a）。
 */
interface TdJson : Library {
    fun td_create_client_id(): Int
    fun td_send(clientId: Int, request: String)
    fun td_receive(timeout: Double): Pointer?
    fun td_execute(request: String): Pointer?

    companion object {
        fun load(): TdJson = Native.load("tdjson", TdJson::class.java)
    }
}

/** 从 td_receive 返回的指针中安全读出 JSON 字符串。 */
fun Pointer?.readJson(): String? = this?.getString(0, "UTF-8")
