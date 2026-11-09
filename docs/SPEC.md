# TG云盘 — 完整规格说明书 v1.0

> 无服务端 Telegram 网盘安卓 App。本文档是全部 29 项设计决策的最终共识，作为唯一实现依据。
> 状态：已评审定案（2026-11-09），待开工。

---

## 1. 概述

### 1.1 一句话定位
用 TG 专用小号的私有频道当无限容量网盘，手机 App 通过 TDLib 直连 MTProto 观看视频/浏览图片，**全程无自建服务端**。

### 1.2 目标
- 秒开流播：4GB 1080p 视频 2 秒起播，随机拖动 1.5 秒内定位
- 海报墙网盘体验：缩略图墙 + 驱动器分区 + 全局搜索 + 继续观看
- 零运维：无后端、无 VPS、无域名，APK 自装自用
- 并发下载提速：1~8 路用户可调（默认 4），非 Premium 聚合 5~8 MB/s

### 1.3 非目标（一期明确不做）
TMDB 刮削 · 字幕（外挂/内嵌都不做）· TV/大屏适配 · 画中画 · 后台音频 · 应用锁 · 常驻后台同步 · 文件树视图 · App 内大文件断点上传 · iOS · 开源

---

## 2. 总体架构

```
┌─────────────────────── TG云盘 App（Android, minSdk 24）────────────────────────┐
│                                                                                │
│  UI 层 (Jetpack Compose, 单 Activity + Navigation)                              │
│  ┌──────────┬──────────┬──────────┬──────────┬──────────┬──────────┐           │
│  │Onboarding│ 海报墙    │ 图片网格  │ 播放页    │ 详情页    │ 设置      │           │
│  │凭据/代理  │(视频)tab │  tab     │PlayerView│ 操作菜单  │并发/缓存  │           │
│  │登录/勾选  │继续观看行 │          │手势/倍速  │删除/改名  │驱动器管理 │           │
│  └──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘           │
│         │ ViewModel(stateFlow)          ▲ ExoPlayer.setMediaSource            │
│  ┌──────▼──────────────────────────────┴─────────────────────────────┐         │
│  │ 应用服务层                                                          │         │
│  │  MediaRepository  DriveManager  IngestReceiver  PlaybackRepo       │         │
│  └──────┬──────────────────────────────┬─────────────────────────────┘         │
│  ┌──────▼──────────┐            ┌──────▼──────────────────────────────┐         │
│  │ Room 索引库      │            │ 下载层 StreamingDownloadManager      │         │
│  │ media/progress  │            │  并发窗口(1~8路)· 512KB 对齐          │         │
│  │ drives/FTS      │            │  partial 文件随机读 · LRU 缓存(1~64G) │         │
│  └─────────────────┘            └──────┬──────────────────────────────┘         │
│         DataStore(凭据/代理/设置)       │ TdDataSource : Media3 BaseDataSource    │
│  ┌────────────────────────────────────▼──────────────────────────────┐         │
│  │ TD 层 TdClient：JNA → libtdjson.so (up9cloud v1.8.65, arm64-v8a)   │         │
│  │  call()/send()/updates Flow · auth 状态机 · addProxy              │         │
│  └────────────────────────────────────┬──────────────────────────────┘         │
└───────────────────────────────────────┼────────────────────────────────────────┘
                                        ▼ MTProto（可经 MTProto/SOCKS5 代理）
                          Telegram 云端：小号名下私有频道 = 网盘存储
```

**分层规则**：UI 只见 ViewModel；Repository 只见 TD 层与 Room；下载层是唯一调用 `downloadFile` 的模块；TD 层是唯一触碰 JNA 的模块。

---

## 3. 技术栈

| 组件 | 选型 | 版本/来源 | 理由 |
|---|---|---|---|
| 语言/平台 | Kotlin + Android 原生 | Kotlin 2.x, compileSdk 35, minSdk 24 | 播放集成层级最少，MVP 最快 |
| UI | Jetpack Compose + Material3 | BOM 最新稳定 | 声明式快速搭海报墙 |
| TDLib | 纯 `libtdjson.so` (td_json_client C 接口) | [up9cloud/android-libtdjson](https://github.com/up9cloud/android-libtdjson) **v1.8.65** `libs.tar.gz`，仅保留 `arm64-v8a/libtdjson.so`（≈10–15MB） | 已核实活跃（2026-06 发版，MIT，CI 构建带 sha256）；JNA 精确匹配；1.8.65 与上游 1.8.70 同 major 二进制兼容。注意：需 16KB page 对齐（Android 15 设备）时改走官方 Docker 自编译（`TDLIB_INTERFACE=JSON`，单 ABI 30–60min） |
| TDLib 绑定 | JNA (`net.java.dev.jna:jna:5.x`) | — | 免写 JNI，`Native.load("tdjson")` 即用 |
| 播放 | Media3 / ExoPlayer 硬解 | `media3-exoplayer`, `media3-ui`, `media3-datasource` | H.264/HEVC 的 MP4/MKV 覆盖主流片源 |
| 索引库 | Room + FTS5 | — | 结构化媒体索引 + 全文搜索 |
| 偏好 | DataStore + EncryptedSharedPreferences | — | api 凭据加密存；代理/并发/缓存明文存 |
| 分页 | Paging 3 | — | 海报墙万级条目流畅滚动 |
| DI | 手写 ServiceLocator | — | 规模小，不上 Hilt |
| 分发 | GitHub Releases（私有仓库） | — | sideload APK |

**兼容性声明**：Media3 对 MKV 内的非常规编码可能跳硬解失败——一期接受（转码/软解在二期池外，引导用户上传前用 `ffmpeg -movflags faststart` 转标准 MP4）。

---

## 4. 模块设计

### 4.1 TD 层 — `TdClient`

单例，持有 client_id 与收发循环。JNA 接口：

```kotlin
interface TdJson : Library {
    fun td_create_client_id(): Int
    fun td_send(clientId: Int, request: String)              // 异步发（request 为 JSON 字符串）
    fun td_receive(timeout: Double): Pointer?                // 阻塞收，返回的指针在下一次调用前有效
    fun td_execute(request: String): Pointer?                // 同步本地方法（setLogVerbosityLevel 等）
}
```

**收发循环**（专用 `THREAD_PRIORITY_BACKGROUND` 线程，daemon）：
- `td_receive(1.0)` 轮询；带 `@extra` 的响应用 `ConcurrentHashMap<String, CompletableDeferred>` 配对唤醒 `call()` 协程
- 不带 `@extra` 的 update（`updateAuthorizationState` / `updateFile` / `updateNewMessage` / `updateDeleteMessages` / `updateChatLastMessage`）按类型分流到三个 `MutableSharedFlow`：`authStateFlow` / `fileUpdateFlow` / `chatUpdateFlow`
- 崩溃保护：receive 循环异常只记日志自愈重启，不炸进程

**鉴权状态机**（由 `updateAuthorizationState` 驱动）：

```
WAIT_OTHER_DEVICE(扫码可用 QR) → WAIT_PHONE_NUMBER → setAuthenticationPhoneNumber
  → WAIT_CODE → checkAuthenticationCode → WAIT_PASSWORD → checkAuthenticationPassword
  → WAIT_REGISTRATION(小号已注册不会出现) → READY
```
错误码处理：`PHONE_CODE_INVALID`/`PHONE_NUMBER_INVALID`/`PASSWORD_HASH_INVALID` → UI 原地重输；连续 3 次失败 → 提示冷却。

**代理**：onboarding 第一步，`addProxy(server, port, enable=true, type)` 支持 MTProto（secret）、SOCKS5 与 HTTP；提供 `testProxy` 延迟测试；可跳过（直连）。代理配置变更 = 重建 client（TDLib 不支持运行时换代理，重建成本可接受）。

**跟随系统代理**（`proxyKind = "system"`）：TDLib 自身不读 Android 系统代理，故由 `SystemProxyResolver` 在启动/登录链路读取
`ConnectivityManager.getLinkProperties(activeNetwork)?.httpProxy`（为空再退回已废弃的 `getDefaultProxy()`），
翻译成 `proxyTypeHttp`（`username`/`password`/`http_only=false`）后交给 `addProxy`。限制：

- 仅 HTTP 代理；TDLib 的 HTTP 代理只做 CONNECT 隧道，不解析 PAC —— `pacFileUrl` 非空而 host 为空时视为「无代理」。
- 忽略 `ProxyInfo.getExclusionList()`（TDLib 无 per-host 绕过能力），宁可全部走代理。
- 探测不到可用代理（未设置 / `isValid()` 为假 / 端口越界）时**退化为直连**，不报错。
- 系统代理只在启动时解析一次；运行中切换 Wi-Fi/代理需重启应用（与手填代理同一约束）。

**TdClient 公开接口**：

```kotlin
class TdClient(private val scope: CoroutineScope) {
    val authState: StateFlow<AuthState>
    val fileUpdates: SharedFlow<JsonObject>      // updateFile 原始流，供下载层订阅
    suspend fun call(query: JsonObject): JsonObject          // 带超时(30s)与 @extra 配对
    fun send(query: JsonObject)                              // fire-and-forget
    fun setProxy(cfg: ProxyConfig?)                          // null = 直连，触发 client 重建
}
```

### 4.2 媒体库层 — `MediaRepository` + `DriveManager`

**驱动器**：用户手动勾选聊天（私有频道/群/Saved Messages）存入 `drives` 表。勾选页用 `getChats(mainChatList)` + 过滤可选类型；Saved Messages 用 `getChat(chat_id=me)` 特判。

**扫描算法**（每个驱动器独立）：
1. 全量首扫：`getChatHistory(chat_id, from_message_id=0, limit=100)` 循环翻页直到头部；每页之间 `delay(150)` 节流（防风控、防 CPU 尖峰）
2. 过滤：`messageVideo` → kind=video；`messagePhoto` → kind=image；`messageDocument` 且 mime 以 `video/` 开头或文件名后缀 ∈ {mp4,mkv,avi,mov,webm,ts,m4v} → kind=video
3. 入库字段见 §5；缩略图用 `downloadFile(file.thumbnail.file, 0, 0, priority=2)` 拉满（几十 KB），落盘 `${cacheDir}/thumbs/{fileUniqueId}.jpg`，路径回写
4. 增量：记录 `last_scanned_message_id`，刷新时从当前 `last_message` 向下扫到已见 message_id 为止；同时订阅 `updateNewMessage`（App 前台期间）实时追加
5. 失效恢复：`file_id` 在 TDLib 库重置后可能失效 → 索引保留 `chat_id+message_id+remote_file_id`，播放前 `getMessage(chat_id, message_id)` 重取 fresh file_id，失败则标记 `stale` 并在下次全扫清理

**删除**：`deleteMessages(chat_id, [message_id], revoke=true)`，成功后由 `updateDeleteMessages` 驱动本地删行（不直接删库，等回执，保证云端真删）。
**重命名**：仅 `UPDATE media SET display_name=?`（Q17 定案：本地显示名覆盖，不碰云端消息）。

### 4.3 下载层 — `StreamingDownloadManager`（全项目核心）

**职责**：把「播放器要任意字节区间」翻译成「TDLib 分片并发下载 + partial 文件随机读」，并对播放已读区域做 LRU 磁盘缓存。

**关键事实**（实现依据）：
- TDLib `downloadFile(file_id, offset, limit, priority)` 的 `offset` 按 512KB（`PART_SIZE`）对齐生效，下载内容追加到该 file 的 `local.partial_path` 文件
- `updateFile` 携带 `local.downloaded_prefix_size`：自**本次请求 offset** 起连续可读的前缀长度；这是「这段已落盘可读」的唯一判据
- `cancelDownloadFile(file_id, only_pending=true)` 只取消排队未启动的分片，已落盘的不丢——seek 后用它避免旧窗口继续烧流量
- 播放中同时 `openChat` 该聊天，可提升服务端文件分发优先级（官方流播同款行为）

**并发窗口逻辑**（伪代码，参数可调）：

```
window = SlidingWindow(file, concurrency=setting(1..8, default 4), chunk=1MB)
ensureRange(offset, len):
    aligned = offset / 512KB * 512KB
    inFlight 请求池补齐：[aligned, aligned + max(4MB, len*2)] 覆盖不足时
        → 对每个缺失 chunk 发 downloadFile(file_id, chunk.offset, 1MB, priority=1)
        → 最多同时 concurrency 路（信号量控制）
    挂起等待 fileUpdates 中该 file 的 downloaded_prefix_size ≥ (offset-aligned+len)
    返回 PartialRef(partialPath, globalOffset=aligned)
read(): 从 partialPath 按 (pos - globalOffset) 实际偏移读出
seek 检测（DataSource.open 新 position）:
    cancelDownloadFile(only_pending=true) → 窗口整体平移到新 aligned，从头补请求
缓存: 播放结束/换片时，把本次 partial 命中的完整 chunk 以 fileUniqueId 为 key
      移入 LRU 池（DataStore 上限 1~64GB，默认 4GB），下次同文件开播先查 LRU 直接读
```

**参数**：`chunk=1MB`，`windowAhead=max(4MB, len*2)`，`concurrency=DataStore 1..8 默认 4`（Q28）。全部集中在 `DownloadSettings` 对象，设置页滑杆实时生效（热更新信号量）。

**容错**：单 chunk 重试 2 次（间隔 1s/3s）；连续失败 → 上抛 `DownloadException`，播放器显示错误条并提供「重试/改代理」按钮；切后台 10 分钟自动 `cancelDownloadFile` 全部并暂停播放（省流量，无后台音频）。

### 4.4 播放层 — `TdDataSource` + `PlayerActivity`

```kotlin
class TdDataSource(private val mgr: StreamingDownloadManager,
                   private val ref: FileRef) : BaseDataSource(false) {
    // open(DataSpec): seek 入口 —— cancelPending + 窗口平移 + transferStarted
    // read(): ensureRange + RandomAccessFile(partialPath).seek/read + bytesTransferred
    // close(): 引用计数归零时释放窗口（LRU 缓存移交）
}
// 组装: DefaultMediaSourceFactory { TdDataSourceFactory(mgr) } + ProgressiveMediaSource
```

**播放页功能清单**（Q18 定案）：
- 手势包：左右横滑 ±15s 快退快进（带 seek 预览浮层）；左侧竖滑亮度；右侧竖滑音量
- 倍速菜单 0.5/0.75/1/1.25/1.5/2/3 + 长按屏幕 2x（松手恢复）
- 双击左/右半屏 ±10s
- 顶部：标题（display_name）、返回；底部：进度条（buffered 二段色=窗口进度）、锁屏按钮（锁定全部手势防误触）
- 续播：`PlaybackRepo` 在 onPause 与每 5s 节流写 `playback_progress`；进入播放页若 position > 30s 且未 finished → 弹「从 xx:xx 继续播放」toast 默认续播
- 错误 UI：软解失败/网络断 → 覆盖层重试按钮

### 4.5 UI 层 — 导航与页面

```
OnboardingFlow（一次性）:
  ① 欢迎/免责(自用声明) → ② api_id/api_hash 输入(校验非空+格式, EncryptedSharedPreferences)
  → ③ 代理配置(MTProto/SOCKS5/跳过, testProxy 测延迟) → ④ 登录(电话/验证码/2FA)
  → ⑤ 驱动器勾选(聊天列表多选) → 进主页
MainScreen (BottomNav 2 tab + FAB 无):
  ├── 海报墙 tab: 顶部驱动器 chip 横滑切换 + 「继续观看」横滑行(有进度未看完的 video)
  │     Grid(3列, 缩略图+时长角标+大小角标) / Paging3 / 顶部搜索框(FTS)
  ├── 图片 tab: 同驱动器切换 + 瀑布流(2列, Photo)
  └── 设置页: 驱动器管理(增删/重扫) · 并发滑杆 1~8 · 缓存上限滑杆 1~64GB+清空
             · 代理修改 · 退出登录 · 关于
详情: 点海报 → 底部弹层(封面大图+元信息+继续播放/重播+重命名+删除+源消息跳转TG)
```

**搜索**：Room FTS5 外部内容表挂 media(display_name)，中文按字索引可满足文件名检索；无拼音/模糊语义（二期池外）。

### 4.6 入库 — `IngestReceiver`

- **转发即入库（主路径）**：用户在任意 TG 客户端把视频/图片转发进驱动器频道 → App 前台时 `updateNewMessage` 实时入库；后台期间靠下次打开的增量扫描补齐（Q21：无常驻后台）
- **Share sheet（辅路径）**：`manifest` 注册 `ACTION_SEND`（video/image）与 `ACTION_SEND_MULTIPLE` → 弹驱动器选择器（Q25：每次弹，带记住默认项勾选）→ `sendMessage(chat_id, inputMessageDocument(inputFileInfo(uri)))` 上传；进度读 `updateFile.uploaded_size`；同时注册 `ACTION_VIEW` 接收tg://消息链接跳转详情页
- **App 内浏览上传**（本地文件挑着传/断点续传）→ 二期

---

## 5. 数据库 Schema（Room）

```sql
CREATE TABLE drives (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id INTEGER UNIQUE NOT NULL,          -- TDLib chat id
  title TEXT NOT NULL,
  order_idx INTEGER NOT NULL DEFAULT 0,
  added_at INTEGER NOT NULL,                -- epoch ms
  last_scanned_message_id INTEGER NOT NULL DEFAULT 0,
  scan_state TEXT NOT NULL DEFAULT 'idle'   -- idle|scanning|done|error
);

CREATE TABLE media (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  drive_id INTEGER NOT NULL REFERENCES drives(id) ON DELETE CASCADE,
  chat_id INTEGER NOT NULL,
  message_id INTEGER NOT NULL,
  kind TEXT NOT NULL,                       -- 'video' | 'image'
  file_id INTEGER NOT NULL,                 -- 可失效, 播放前 getMessage 重取
  remote_file_id TEXT NOT NULL,
  file_unique_id TEXT NOT NULL,             -- LRU 缓存 key / 缩略图文件名
  display_name TEXT NOT NULL,               -- 含本地覆盖重命名
  original_name TEXT NOT NULL,
  mime TEXT,
  size INTEGER NOT NULL,
  duration_ms INTEGER,                      -- image 为 NULL
  width INTEGER, height INTEGER,
  message_date INTEGER NOT NULL,            -- epoch s
  thumb_path TEXT,                          -- 本地缩略图缓存路径, 可 NULL
  stale INTEGER NOT NULL DEFAULT 0,         -- file_id 失效待重取
  UNIQUE(chat_id, message_id)
);
CREATE INDEX idx_media_wall ON media(drive_id, kind, message_date DESC);
CREATE VIRTUAL TABLE media_fts USING fts5(display_name, content='media', content_rowid='id');

CREATE TABLE playback_progress (
  media_id INTEGER PRIMARY KEY REFERENCES media(id) ON DELETE CASCADE,
  position_ms INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL,
  finished INTEGER NOT NULL DEFAULT 0,      -- ≥95% 或播完置 1, 不再进继续观看
  updated_at INTEGER NOT NULL
);
```

偏好（Room 外，DataStore）：`api_id/api_hash`(EncryptedSharedPreferences)、`proxy`、`download_concurrency`、`cache_cap_gb`、`share_default_drive`、`onboarded`。

---

## 6. 关键接口签名汇总

```kotlin
// TD 层
class TdClient(scope) { authState; fileUpdates; suspend call(JsonObject); send(JsonObject); setProxy(ProxyConfig?) }

// 下载层
class StreamingDownloadManager(td, scope, settings) {
    suspend fun ensureRange(fileId: Int, offset: Long, len: Int): PartialRef
    fun cancelPending(fileId: Int); fun cancelAll()
    fun setConcurrency(n: Int)                       // 热更新
    fun cacheDirFor(fileUniqueId: String): File      // LRU 缓存命中直接读
}
class TdDataSource(mgr, ref) : BaseDataSource(false) // open/read/close → 窗口平移/ensureRange/释放

// 媒体库
class MediaRepository(db, td) {
    suspend fun fullScan(driveId); suspend fun incrementalSync(driveId)
    fun observeWall(driveId, kind): Flow<PagingData<MediaItem>>
    fun observeContinueWatching(): Flow<List<MediaItem>>
    suspend fun search(q: String): List<MediaItem>
    suspend fun delete(item); suspend fun rename(item, newName)
    suspend fun freshFileId(item): Int               // getMessage 重取, 失败置 stale
}

// 入库
class IngestReceiver { fun onShared(uris, driveId): Deferred<List<UploadState>> }
```

---

## 7. 风控与合规守则（自用边界）

1. 下载并发硬上限 8（滑杆顶格），上传并发 ≤ 2 文件；分页扫描每页 `delay(150ms)`
2. 频道文件总量无上限，但**上传速率**保守：单文件上传不限速、多文件排队
3. 不实现：多 session 轮询、bot farm、爬虫式全账号扫描——单账号官方客户端同款行为模式
4. 免责文案（onboarding 第①步）：「TG 云端无 SLA，请为重要内容保留独立备份；本应用仅供个人自用」
5. 小号原则：所有存储/传输仅发生在专用小号，主号零接触（Q2）

---

## 8. 错误处理矩阵

| 场景 | 检测 | 处置 |
|---|---|---|
| 登录码错/密码错 | auth error | UI 原地重输，3 次冷却提示 |
| 代理失效 | testProxy 失败 / 大量 FLOOD_WAIT | 播放错误条「改代理」快捷跳设置 |
| FLOOD_WAIT_N | td error `FLOOD_WAIT` | 严格 sleep N 秒后重试；>120s 提示用户稍后 |
| file_id 失效 | downloadFile FILE_LOST / getMessage null | freshFileId 重取 → 仍败置 stale，海报墙灰显 |
| 软解不支持 | ExoPlayer onPlayerError | 覆盖层说明「该编码 Media3 不支持，建议转 MP4(H.264+AAC) 后重传」 |
| 磁盘满（LRU 写入） | IOException ENOSPC | 立即触发 LRU 逐出后重试，仍败则禁用缓存纯流播 |
| TDLib 崩溃 | receive 循环异常 | 自愈重启 client → 自动重登录态（TDLib 持久库保留会话） |

---

## 9. 里程碑与验收

### M1 — 骨架 + 登录 + 代理 + 驱动器（3~5 天）
工程搭好（Compose + Room + TdClient + jniLibs 单 so）；onboarding 五步全通；真机登录小号到 READY；勾选 2 个驱动器入库。
**验收**：杀进程重开 5 秒内恢复 READY（TDLib 持久会话）；飞行模式切换代理不崩。

### M2 — 扫描索引 + 海报墙/图片网格 + 搜索（4~5 天）
全扫+增量+缩略图缓存；两 tab 视图 + 继续观看行占位；FTS 搜索。
**验收**：≥1000 条消息频道全扫 ≤ 60s；海报墙 Paging 滑动无卡顿（Systrace 无掉帧>700ms）；搜索「文件名片段」命中 <200ms；图片 tab 瀑布流正常。

### M3 — 流式播放（5~7 天，核心攻坚）
TdDataSource 顺序流播 → 并发窗口 → seek 平移 → LRU 缓存 → 手势/倍速/双击/续播 UI。
**验收**：4GB 1080p MP4 起播 ≤ 2s；随机 seek 定位 ≤ 1.5s；并发滑杆 1 vs 8 实测带宽差可见；MKV(H.264) 可播；中断网缓冲自动恢复播放；续播跨进程重启准确。

### M4 — 入库 + 管理 + 收尾（3~4 天）
Share sheet 上传（带进度通知条）；删除/重命名；stale 清理；设置页滑杆全量接线；错误矩阵全覆盖。
**验收**：系统相册分享 500MB 视频入库成功且海报墙出现；删除后官方 TG App 里消息同步消失；重命名仅本地生效；缓存逐出后重复观看回源正常。

**MVP 总量：15~21 人日。**

---

## 10. 二期池（按优先级）

1. App 内断点续传大文件上传（分片队列）
2. TMDB 刮削 + 文件树视图
3. 字幕（外挂 srt → 自动配对）
4. TV/Leanback 适配 · 后台音频 · 画中画
5. 抽帧高清海报 · 离线下载队列 · 应用锁

---

## 11. 附录 A — TDLib 方法清单（本项目用到）

`setTdlibParameters` `setAuthenticationPhoneNumber` `checkAuthenticationCode` `checkAuthenticationPassword` `getAuthorizationState` `addProxy` `testProxy` `getChats` `getChat` `getChatHistory` `getMessage` `searchChatMessages`(空查询=翻页备选) `downloadFile` `cancelDownloadFile` `openChat` `closeChat` `deleteMessages` `sendMessage`(+`inputMessageDocument`/`inputFileLocal`)

## 附录 B — 事实依据存档

- TDLib 预编译：up9cloud/android-libtdjson v1.8.65（2026-06-30，MIT，CI 带 sha256）为唯一采用源；akdasa-studios/mensa-/iwantbo 三个候选经 GitHub API 核实为 404 虚构；TGX-Android/tdlib 明文仅供 Telegram X；AkashPriyadarshii 版 Maven Central 虚标且接口自相矛盾，均排除
- 官方自编译兜底：tdlib/td `example/android`（2026-10 维护中，支持单 ABI），Docker `--build-arg TDLIB_INTERFACE=JSON`
- TDLib 版本兼容：JSON 接口同 major 二进制兼容（官方 README），1.8.65 ↔ 1.8.70 可互换
- 限速模型：单连接 ≈1MB/s（非 Premium），并发 4~8 路聚合 5~8MB/s；Premium 单连接 8MB/s 聚合 25MB/s+
