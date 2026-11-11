package com.yg.tgdrive

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃日志：把未捕获异常的完整堆栈落到 `filesDir/crashes/crash-<yyyyMMdd-HHmmss>.txt`。
 *
 * 设计约束：
 * - **不吞崩溃**：写盘后必须把控制权交还原来的 handler（`defaultHandler`），
 *   系统崩溃对话框 / 进程终止 / 重启行为完全不变。安装前先保存旧 handler。
 * - **写盘失败绝不再抛**：整个落盘路径包在 `runCatching` 里，失败只打 logcat；
 *   异常处理器里再抛异常会导致进程无提示死亡、且原始堆栈丢失。
 * - **不申请 READ_LOGS**：logcat 需要系统级权限，普通应用拿不到；因此不采集，
 *   只用堆栈 + 设备/版本信息定位问题。
 * - **只留最近 [MAX_FILES] 个**：按 `lastModified` 从旧到新删（同一秒内多次崩溃会带
 *   `-N` 后缀，文件名排序不可靠），避免长期使用把存储吃满。
 *
 * 全部方法可在任意线程调用（含崩溃线程）；写盘/清理用 [LOCK] 串行化，
 * 因为多个线程同时崩溃（如主线程 + 协程线程）时 handler 会被并发调用。
 */
object CrashLogger {

    /** 崩溃目录名（相对 `filesDir`）。 */
    const val DIR_NAME = "crashes"

    /** 保留的最新崩溃日志数量。 */
    const val MAX_FILES = 10

    private const val TAG = "CrashLogger"
    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".txt"

    private val LOCK = Any()

    @Volatile
    private var appContext: Context? = null

    /**
     * 安装全局未捕获异常处理器。幂等：重复调用只保留最早安装时保存的原始 handler，
     * 不会形成 handler 链式套娃。
     *
     * 必须在 `Application.onCreate()` 的第一行（任何 ServiceLocator / TDLib 初始化之前）调用，
     * 否则初始化阶段的崩溃无法被捕获。
     */
    fun install(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 1) 尽力落盘；任何失败都只记录，不改变崩溃流程
            runCatching { writeCrash(context.applicationContext, thread, throwable) }
                .onFailure { Log.e(TAG, "write crash log failed", it) }
            // 2) 原样交还系统/旧 handler —— 崩溃语义不变。
            //    Android 上 defaultHandler 恒为 RuntimeInit 的 KillApplicationHandler，不会为 null；
            //    万一为 null，手动杀进程，避免"吞掉崩溃"导致进程半死。
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** 崩溃目录（不保证存在）。 */
    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 当前保留的崩溃日志数量（供设置页展示）。 */
    fun count(context: Context): Int = synchronized(LOCK) { listCrashFiles(context).size }

    /** 删除全部崩溃日志，返回删除后剩余数量（正常为 0）。 */
    fun clear(context: Context): Int = synchronized(LOCK) {
        listCrashFiles(context).forEach { runCatching { it.delete() } }
        listCrashFiles(context).size
    }

    // ---------------------------------------------------------------- 内部实现

    private fun listCrashFiles(context: Context): List<File> {
        val files = dir(context).listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        } ?: return emptyList()
        // 按修改时间排序（同一秒内多次崩溃会带 -N 后缀，文件名排序不可靠）
        return files.sortedByDescending { it.lastModified() }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) = synchronized(LOCK) {
        val crashDir = dir(context)
        if (!crashDir.isDirectory && !crashDir.mkdirs()) return

        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        // 同一秒内多次崩溃（主线程 + 协程线程并发）会撞名；加 -N 后缀保证一条一个文件
        var file = File(crashDir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        var seq = 1
        while (file.exists()) {
            file = File(crashDir, "$FILE_PREFIX$stamp-${++seq}$FILE_SUFFIX")
        }

        val body = buildString {
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(now)}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("构建类型: ${if (BuildConfig.DEBUG) "debug" else "release"}")
            appendLine(
                "设备: ${Build.MODEL} / Android ${Build.VERSION.RELEASE} / " +
                    "SDK ${Build.VERSION.SDK_INT} / ${Build.SUPPORTED_ABIS.joinToString(",")}"
            )
            appendLine("线程: ${thread.name} (id=${thread.id})")
            appendLine("异常: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("---- 堆栈 ----")
            appendLine(stackTraceOf(throwable))
        }

        // 直接写；进程随后就死，无需原子替换
        file.writeText(body)
        Log.e(TAG, "crash logged to ${file.absolutePath}")

        prune(crashDir)
    }

    /** 只保留最新 [MAX_FILES] 个，其余按修改时间从旧到新删除。 */
    private fun prune(dir: File) {
        val all = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        } ?: return
        if (all.size <= MAX_FILES) return
        all.sortedBy { it.lastModified() }    // 最老在前
            .take(all.size - MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        return sw.toString()
    }
}
