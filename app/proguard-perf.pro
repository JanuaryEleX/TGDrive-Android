# perf 构建专用 ProGuard 规则（在 proguard-rules.pro 之上叠加）
#
# perf 的目标：拿到接近 release 的代码质量（R8 全开），
# 同时保留能定位崩溃的符号信息，并确保调试诊断仍可用。

# 保留行号，崩溃栈可读（不保留源文件名，减小体积）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# perf 是 debuggable 的，崩溃日志要能看清调用链
-keep class com.yg.tgdrive.CrashLogger { *; }

# 播放/扫描诊断（PlaybackDiag / ScanDiagnostics）在 perf 下仍要能输出 JSON，
# 它们的字段名会被 kotlinx.serialization 反射读取，不能混淆
-keep class com.yg.tgdrive.download.PlaybackDiag { *; }
-keep class com.yg.tgdrive.data.ScanDiagnostics { *; }

# Room 实体与 DAO 由生成的 Impl 直接引用，R8 通常能处理；
# 显式保留实体类字段名，便于用 sqlite3 直接查库对照
-keepclassmembers class com.yg.tgdrive.data.*Entity { *; }
