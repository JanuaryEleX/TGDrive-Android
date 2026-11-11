package com.yg.tgdrive

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.withTimeoutOrNull
import com.yg.tgdrive.td.AuthState

/**
 * Application 入口：初始化 ServiceLocator + 冷启动恢复链。
 * 恢复链：已保存代理 → 应用代理；凭据（自定义或内置默认）→ TDLib initialize（恢复会话）。
 */
class TgDriveApp : Application() {

    private companion object {
        /** 冷启动每一步的上限，避免任一步挂起把整条恢复链堵死。 */
        const val STARTUP_STEP_TIMEOUT_MS = 30_000L
    }

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ServiceLocator.appScope.launch {
            val sl = ServiceLocator
            // 顺序关键：TDLib 收到 setTdlibParameters 之前不会正常应答其它请求。
            // 旧顺序（先 applySnapshot 后 initialize）下 addProxy 的 await 永久挂起，
            // initialize 永远执行不到，authState 卡在 Loading（界面一直"正在连接 Telegram…"）。
            val (apiId, apiHash) = sl.credentials.getOrBuiltIn()
            runCatching {
                withTimeoutOrNull(STARTUP_STEP_TIMEOUT_MS) {
                    sl.tdClient.initialize(apiId, apiHash, sl.tdDatabaseDir, sl.tdFilesDir)
                }
            }.onFailure { Log.e("TgDriveApp", "TDLib initialize failed", it) }
            runCatching {
                sl.settings.proxy.first()?.let { snapshot ->
                    withTimeoutOrNull(STARTUP_STEP_TIMEOUT_MS) {
                        sl.proxyManager.applySnapshot(snapshot, sl.context)
                    }
                }
            }.onFailure { Log.e("TgDriveApp", "apply proxy failed", it) }

            // 看门狗：25s 仍未 Ready 就重试一轮初始化 + 代理，
            // 避免单次请求异常把会话永久钉在 Loading（界面一直"正在连接 Telegram…"）。
            val ready = withTimeoutOrNull(25_000L) {
                sl.tdClient.authState.first { it is AuthState.Ready }
            }
            if (ready == null) {
                Log.w("TgDriveApp", "authState not Ready after 25s, retrying init + proxy")
                runCatching {
                    withTimeoutOrNull(STARTUP_STEP_TIMEOUT_MS) {
                        sl.tdClient.initialize(apiId, apiHash, sl.tdDatabaseDir, sl.tdFilesDir)
                    }
                }.onFailure { Log.e("TgDriveApp", "retry initialize failed", it) }
                runCatching {
                    sl.settings.proxy.first()?.let { snapshot ->
                        withTimeoutOrNull(STARTUP_STEP_TIMEOUT_MS) {
                            sl.proxyManager.applySnapshot(snapshot, sl.context)
                        }
                    }
                }.onFailure { Log.e("TgDriveApp", "retry apply proxy failed", it) }
            }

            // TDLib 就绪后启动 v1→v2 相册 ID 回填（后台，不阻塞 UI）。
            // 用 sl.context（Application）而非 launch 作用域的 this（CoroutineScope）。
            sl.scheduleAlbumBackfill(sl.context)
        }
    }
}
