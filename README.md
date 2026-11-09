# TG云盘 (TGDrive)

> 无服务端 Telegram 网盘安卓 App —— 用 TG 专用小号的私有频道当无限容量网盘，通过 TDLib 直连 MTProto 流式观看视频 / 浏览图片，**全程无自建服务端**：无后端、无 VPS、无域名，APK 自装自用。

- 4GB 1080p 视频 2 秒起播，随机拖动 1.5 秒内定位（并发分片下载 + LRU 缓存）
- 海报墙网盘体验：缩略图墙 + 驱动器分区 + 全局搜索 + 继续观看
- 并发下载 1~8 路可调（默认 4），非 Premium 聚合 5~8 MB/s
- 仅供个人自用（TG 云端无 SLA，重要内容请保留独立备份）

完整设计规格见 **[docs/SPEC.md](docs/SPEC.md)**（唯一实现依据，含全部 29 项设计决策）。

## 前置条件

| 依赖 | 版本要求 | 说明 |
|---|---|---|
| Android Studio | 最新稳定版 | 主 IDE |
| JDK | 17 | Gradle / AGP 工具链 |
| Android SDK | 35 (Android 15) | compileSdk / targetSdk = 35 |
| NDK | **不需要** | TDLib 用预编译 so，纯 JNA 直调，无本地代码要编 |

设备要求：**arm64-v8a 真机**（Android 7.0 / API 24+）。模拟器不可用（只打包 arm64-v8a 单 ABI）。

## 快速开始

1. **拉取 TDLib 库**（首次必做，~41 MB 下载）：
   ```powershell
   # Windows (PowerShell 5.1 / 7 均可)
   powershell -ExecutionPolicy Bypass -File scripts\fetch-tdlib.ps1
   ```
   ```bash
   # macOS / Linux / WSL / Git-Bash
   ./scripts/fetch-tdlib.sh
   ```
   脚本幂等：so 已存在则跳过；加 `-Force` / `--force` 强制重拉。完成后产物在
   `app/src/main/jniLibs/arm64-v8a/libtdjson.so`（≈10-15 MB，4 ABI 源包只保留 arm64-v8a，带官方 SHA256 校验）。

2. **打开工程**：Android Studio 打开 `tgdrive/` 目录，Sync & Build。

3. **申请 API 凭据**：到 [my.telegram.org](https://my.telegram.org) → API development tools，用小号注册一个应用，拿到 `api_id` / `api_hash`（App 首次启动的 Onboarding 会引导填入，加密存储在本地）。

4. **真机安装登录**：连接 arm64 真机 Run 安装；用**专用小号**登录（主号零接触，见 SPEC §7 小号原则）。建议先在 Onboarding 配好 MTProto/SOCKS5 代理（可跳过直连）。

## 目录结构

```
tgdrive/
├── scripts/
│   ├── fetch-tdlib.ps1        # 拉取 libtdjson.so（Windows 主脚本）
│   └── fetch-tdlib.sh         # 同功能的 bash 版（macOS/Linux/WSL）
├── docs/
│   └── SPEC.md                # ★ 完整规格说明书（架构/模块/Schema/里程碑）
├── app/
│   └── src/main/
│       ├── java/com/yg/tdrive/
│       │   ├── td/            # TD 层：TdClient(JNA 收发循环)· TdJson · TdProxyManager
│       │   ├── settings/      # SettingsStore(DataStore) · CredentialsStore(加密凭据)
│       │   ├── ingest/        # 分享入库：ShareReceiverActivity（M4 实现 sendMessage 上传）
│       │   ├── download/      # StreamingDownloadManager · TdDataSource（并发窗口/LRU）
│       │   ├── data/          # Room: AppDatabase · MediaRepository · DriveManager · DAO
│       │   ├── ui/            # Compose: MainActivity · 海报墙/图片/播放/设置页
│       │   └── ServiceLocator.kt  # 手写 DI 接线中心（其他模块由此取依赖）
│       ├── jniLibs/arm64-v8a/ # libtdjson.so（由 fetch 脚本生成，不入库）
│       ├── res/
│       └── AndroidManifest.xml
├── build.gradle.kts / settings.gradle.kts / gradle/
└── README.md
```

## 关键说明

- **TDLib 库**：[up9cloud/android-libtdjson](https://github.com/up9cloud/android-libtdjson) v1.8.65（2026-06-30 发版，MIT；纯 `libtdjson.so` 集合，td_json_client C 接口，经 JNA `Native.load("tdjson")` 直调）。选型依据与候选排除记录见 [docs/SPEC.md](docs/SPEC.md) 附录 B。
- **so 不入 Git**：`.gitignore` 已排除 `app/src/main/jniLibs/**/*.so`，换机 / CI 必须先跑 fetch 脚本。
- **16KB page 对齐**：Android 15 设备如遇兼容问题，需改走官方 Docker 自编译（`TDLIB_INTERFACE=JSON`，单 ABI 30-60 min），见 SPEC §3 兼容性声明。
- **模块分层规则**（SPEC §2）：UI 只见 ViewModel；Repository 只见 TD 层与 Room；下载层是唯一调用 `downloadFile` 的模块；TD 层是唯一触碰 JNA 的模块。
