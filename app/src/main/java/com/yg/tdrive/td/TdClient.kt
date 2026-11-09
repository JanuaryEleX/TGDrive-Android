package com.yg.tgdrive.td

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

sealed interface AuthState {
    data object Loading : AuthState
    data object WaitPhoneNumber : AuthState
    data object WaitCode : AuthState
    data object WaitPassword : AuthState
    data object Ready : AuthState
    data class Error(val message: String) : AuthState
    data class Closed(val message: String) : AuthState
}

/**
 * TDLib JSON 客户端：单例收发循环 + @extra 配对 + update 分流。
 * SPEC §4.1。唯一允许触碰 JNA 的类（TdJson 除外）。
 */
class TdClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val api: TdJson by lazy { TdJson.load() }
    private val clientId: Int by lazy { api.td_create_client_id() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _fileUpdates = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val fileUpdates: SharedFlow<JsonObject> = _fileUpdates.asSharedFlow()

    private val _chatUpdates = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val chatUpdates: SharedFlow<JsonObject> = _chatUpdates.asSharedFlow()

    private val _authErrors = MutableSharedFlow<JsonObject>(extraBufferCapacity = 16)
    val authErrors: SharedFlow<JsonObject> = _authErrors.asSharedFlow()

    @Volatile private var running = true
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        api.td_execute("""{"@type":"setLogVerbosityLevel","new_verbosity_level":1}""")
        thread(name = "td-receive", isDaemon = true, priority = Thread.MIN_PRIORITY + 2) {
            receiveLoop()
        }
        // 启动鉴权探测：发送空 updateAuthorizationState 请求触发 TDLib 下发当前状态
        send(tdObj("@type" to "getOption", "name" to "version"))
    }

    /** 异步请求 + @extra 配对等待响应（30s 超时由调用方包 withTimeout）。 */
    suspend fun call(query: JsonObject): JsonObject {
        val extra = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[extra] = deferred
        try {
            api.td_send(clientId, query.withExtra(extra).toString())
            return deferred.await()
        } finally {
            pending.remove(extra)
        }
    }

    /**
     * 鉴权前置：注册 api 凭据 + 初始化 TDLib 数据库（必须在登录前调用一次）。
     * 未调用时 TDLib 不会下发 authorizationStateWaitPhoneNumber。
     * 幂等：setTdlibParameters 二次调用会报错，进程内只生效第一次。
     */
    suspend fun initialize(apiId: Int, apiHash: String, databaseDir: String, filesDir: String) {
        if (!initialized.compareAndSet(false, true)) return
        java.io.File(databaseDir).mkdirs()
        java.io.File(filesDir).mkdirs()
        // TDLib 1.8.x 校验 system_language_code 非空（模拟器系统 locale 可能为空 → 400
        // "System language code must be non-empty"）——显式传并兜底非空。
        val lang = java.util.Locale.getDefault().language?.takeIf { it.isNotBlank() } ?: "en"
        call(tdObj(
            "@type" to "setTdlibParameters",
            "api_id" to apiId,
            "api_hash" to apiHash,
            "device_model" to (android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"),
            "system_version" to ("Android " + (android.os.Build.VERSION.RELEASE ?: "")).trim(),
            "application_version" to "0.1.0",
            "system_language_code" to lang,
            "database_directory" to databaseDir,
            "files_directory" to filesDir,
            "use_file_database" to true,
            "use_chat_info_database" to true,
            "use_message_database" to true,
            "use_secret_chats" to false,
            "enable_storage_optimizer" to true,
        ))
    }

    /** 登录链路：手机号 → 验证码 → 2FA。 */
    suspend fun submitPhoneNumber(phone: String) =
        call(tdObj("@type" to "setAuthenticationPhoneNumber", "phone_number" to phone))

    suspend fun submitCode(code: String) =
        call(tdObj("@type" to "checkAuthenticationCode", "code" to code))

    suspend fun submitPassword(password: String) =
        call(tdObj("@type" to "checkAuthenticationPassword", "password" to password))

    /** fire-and-forget。 */
    fun send(query: JsonObject) {
        api.td_send(clientId, query.toString())
    }

    private fun JsonObject.withExtra(extra: String): JsonObject {
        val map = LinkedHashMap<String, JsonElement>(size + 1)
        map.putAll(this)
        map["@extra"] = JsonPrimitive(extra)
        return JsonObject(map)
    }

    private fun receiveLoop() {
        while (running) {
            try {
                val raw = api.td_receive(1.0).readJson() ?: continue
                val obj = json.parseToJsonElement(raw).jsonObject
                dispatch(obj)
            } catch (t: Throwable) {
                // 自愈：记录并继续，不炸进程（SPEC §8）
                android.util.Log.e("TdClient", "receiveLoop error", t)
            }
        }
    }

    private fun dispatch(obj: JsonObject) {
        val type = obj["@type"]?.let { (it as? JsonPrimitive)?.content } ?: ""

        // ① @extra 配对
        val extra = (obj["@extra"] as? JsonPrimitive)?.content
        if (extra != null) {
            pending.remove(extra)?.complete(obj)
            if (type.startsWith("error")) dispatchError(obj)
            return
        }

        when (type) {
            "updateAuthorizationState" -> {
                val s = obj["authorization_state"] as? JsonObject
                _authState.value = mapAuthState(s?.get("@type")?.let { (it as? JsonPrimitive)?.content } ?: "")
            }
            "updateFile" -> _fileUpdates.tryEmit(obj)
            "updateNewMessage", "updateDeleteMessages", "updateChatLastMessage" ->
                _chatUpdates.tryEmit(obj)
            "error" -> dispatchError(obj)
        }
    }

    private fun dispatchError(obj: JsonObject) {
        val code = (obj["code"] as? JsonPrimitive)?.content
        val msg = (obj["message"] as? JsonPrimitive)?.content
        if (code != null && msg != null) {
            _authErrors.tryEmit(obj)
            android.util.Log.w("TdClient", "td error $code: $msg")
        }
    }

    private fun mapAuthState(t: String): AuthState = when (t) {
        "authorizationStateWaitPhoneNumber" -> AuthState.WaitPhoneNumber
        "authorizationStateWaitCode" -> AuthState.WaitCode
        "authorizationStateWaitPassword" -> AuthState.WaitPassword
        "authorizationStateReady" -> AuthState.Ready
        "authorizationStateClosed" -> AuthState.Closed("会话已关闭")
        else -> AuthState.Loading
    }

    fun close() {
        running = false
        pending.values.forEach { it.cancel() }
        send(tdObj("@type" to "close"))
    }
}

/** 便捷 JsonObject 构造。 */
fun tdObj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
    LinkedHashMap<String, JsonElement>().apply {
        pairs.forEach { (k, v) ->
            put(k, when (v) {
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is JsonObject -> v
                is JsonElement -> v
                else -> JsonPrimitive(v.toString())
            })
        }
    }
)
