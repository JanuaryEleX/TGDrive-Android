package com.yg.tgdrive

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application 入口：初始化 ServiceLocator + 冷启动恢复链。
 * 恢复链：已保存代理 → 应用代理；凭据（自定义或内置默认）→ TDLib initialize（恢复会话）。
 */
class TgDriveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ServiceLocator.appScope.launch {
            val sl = ServiceLocator
            sl.settings.proxy.first()?.let { snapshot ->
                runCatching { sl.proxyManager.apply(snapshot.toConfig()) }
            }
            val (apiId, apiHash) = sl.credentials.getOrBuiltIn()
            runCatching {
                sl.tdClient.initialize(apiId, apiHash, sl.tdDatabaseDir, sl.tdFilesDir)
            }
        }
    }
}
