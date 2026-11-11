# TG 云盘 · UI 重设计说明

> 目标：把「简陋能用」的界面改造成影音应用级别的观感。
> 参考基线：CloudStream、Aniyomi、Next Player —— 深色近黑底、单一强调色、图标全矢量、海报本身承载信息。

---

## 1. 为什么原来丑

原界面丑在四处，不是「颜色不好看」这么简单：

| 问题 | 具体表现 |
|---|---|
| **海报上叠字** | 一个 2:3 小格子里塞了 4 层：两行标题（`padding(vertical=20.dp)`）、时长角标、大小文字、右上角「i」圆点。信息密度过高，图被压死。 |
| **描边控件** | `OutlinedTextField` 24dp 圆角 + `FilterChip` 描边，在深色底上是四条灰线，显得廉价。 |
| **emoji 当图标** | 播放器用 `⏮ ⏸ ⏭` 文本当按钮，字重、基线、点击区全不可控。 |
| **配色不统一** | 底色 `#17212B`（Telegram 桌面旧色）+ 红 `#E53935` + 一堆零散硬编码色。 |

---

## 2. 设计令牌

全部集中在 `ui/common/AppTheme.kt`，其他地方只引用 `MaterialTheme.colorScheme.*`。

### 2.1 配色（深色影音风）

| 令牌 | 值 | 用途 |
|---|---|---|
| `background` | `#0A0D12` | 页面底，近黑带一点蓝 |
| `surface` | `#10151C` | 卡片底 |
| `surfaceContainerLowest` | `#0C1015` | 最深容器 |
| `surfaceContainerLow` | `#0E131A` | 底部导航栏 |
| `surfaceContainer` | `#10161E` | 骨架屏占位、卡片 |
| `surfaceContainerHigh` | `#141A23` | 搜索框、圆形图标底 |
| `surfaceContainerHighest` | `#182029` | 最高层容器 |
| `primary` | `#4EA1FF` | 唯一强调色（BrandBlue） |
| `onPrimary` | `#04121F` | 主色上的文字 |
| `onBackground` / `onSurface` | `#E6EDF5` | 正文 |
| `onSurfaceVariant` | `#8A98A8` | 次要文字 |
| `error` | `#FF6B6B` | 危险操作 |
| `outline` / `outlineVariant` | `#2A3542` / `#1C2530` | 极少数需要的描边 |

**关键决策：只用一个强调色。** 蓝 `#4EA1FF` 出现在进度条、选中 tab、播放按钮、倍速激活态、搜索光标。红只给删除。没有第二种品牌色。

### 2.2 字体

`AppTypography`：

- `headlineMedium` 25sp / Bold / letterSpacing `-0.4` —— 顶栏大标题
- `titleMedium` 15sp / SemiBold —— 分区标题、详情标题
- `bodyMedium` 13.5sp —— 正文
- `bodySmall` 12sp —— 副标题、元信息
- `labelMedium` 12sp —— 海报标题
- `labelSmall` 10.5sp —— 角标、底部 tab

字号整体比 Material3 默认小 1–1.5sp。深色底上字重显得更重，缩小一档反而更清晰。

### 2.3 圆角

`AppShapes`：`extraSmall 6dp` → `small 10dp` → `medium 14dp` → `large 20dp` → `extraLarge 28dp`。

实际使用：海报 `11dp`、搜索框/胶囊按钮 `50%`（全圆）、详情封面 `14dp`、加载卡 `20dp`。

---

## 3. 图标

`ui/common/AppIcons.kt` —— 24×24 `ImageVector.Builder` 内联 path data，全部 `by lazy`。

```
Play Pause SkipPrevious SkipNext Lock LockOpen AspectRatio Speed
Star StarOutline Info Download Share Delete Close Check More
Movie Image History Search Settings ArrowBack ChevronRight
```

**为什么不加 `material-icons-extended`**：那个包 ~10 MB，只为二十几个图标不值得。内联 path 总量 7.8 KB。

---

## 4. 逐屏改造

### 4.1 主页 `MainScreen`

- 顶栏从「大标题 + 副标题 + 设置按钮」改为**小标题随 tab 变化**（最近/视频/图片），副标题一行说明。设置按钮 38dp 圆形 `surfaceContainerHigh` 底。
- 底部 `NavigationBar` 三 tab：最近（History）/ 视频（Movie）/ 图片（Image）。选中态 = 主色图标 + 主色文字 + `primary.copy(alpha=0.14)` 药丸底。
- `tonalElevation = 0`，容器色 `surfaceContainerLow`，与页面底形成极轻的层次。

### 4.2 海报墙 `WallTab`

**最大的改动：把信息从图上搬到图下。**

```
┌─────────────┐
│             │  ← 2:3 缩略图，11dp 圆角
│   [2:21:33] │  ← 时长：图内左下，黑 0.55 胶囊
│           ⋯ │  ← 详情：图内右下，20dp 半透明圆
└─────────────┘
 DASS-160 森沢  ← 标题：图下，2 行，14.5sp 行高
 かな.MP4
```

- 图上只剩**两个**元素：时长胶囊、详情圆点。原来的大小文字、右上角「i」全部删除。
- 底部加 46dp `Brush.verticalGradient` 透明→黑 0.6，只为托住角标，不压暗画面。
- 3 列 `GridCells.Fixed(3)`，间距 9dp / 14dp，外边距 16dp。
- 点击 = 直接播放（携带当前列表 400 条为播放队列）；长按 = 详情。

**搜索框**：从 `OutlinedTextField` 改成自绘胶囊 —— `RoundedCornerShape(50)` + `surfaceContainerHigh` 填充 + `BasicTextField`。有内容时右侧出现 18dp 清除圆点。

**驱动器 chip**：自绘，选中 `primary.copy(alpha=0.16)` 底 + 主色文字，未选中 `surfaceContainerHigh` + 次要文字。无描边。

**骨架屏**：加载中显示 12 个 `surfaceContainer` 灰块（2:3 + 一条 9dp 标题条），避免白屏抖动。

**空态**：64dp 圆形图标底 + 标题 + 两行说明，统一 `EmptyState`。

### 4.3 最近 tab `RecentTab`

「继续观看」横滑（176dp 宽 16:9 卡）+ 「最近入库」3 列网格，复用 `PosterCell`。

### 4.4 图片 tab `GalleryTab`

2 列瀑布流，按原始宽高比（`coerceIn(0.55f, 2.0f)`）。图内底部只留一行文件大小。骨架屏 8 块按 0.75 / 1.33 / 1.0 三种比例交错。

### 4.5 详情弹层 `MediaDetailSheet`

```
┌────────────────────┐
│     16:9 封面       │  ← 14dp 圆角，底部渐变，左下时长胶囊
└────────────────────┘
 标题（最多 3 行）
 大小 · 时长 · 1920×1080 · 日期
 ┌──────────┬────────┐
 │ ▶ 播放    │  从头播  │  ← 主色实心胶囊 + 半透明胶囊
 └──────────┴────────┘
   ⤴      ⓘ      🗑
  分享   详情   删除      ← 44dp 圆底 + 图标 + 标签
```

- 主操作用**实心主色胶囊**（`RoundedCornerShape(50)`，13dp 垂直内距），次操作用 `surfaceContainerHigh` 半透明胶囊。
- 图标动作 44dp 圆底，删除用 `error.copy(alpha=0.12)` 底 + `error` 图标。
- 删除走 `AlertDialog` 二次确认，容器色 `surfaceContainerHigh`。

### 4.6 播放器 `PlayerActivity`

**顶栏**：三段式 `Brush.verticalGradient`（黑 0.82 → 0.42 → 透明），`statusBarsPadding`。返回 22dp 矢量图标；标题 15sp SemiBold，下行 meta 11sp `alpha 0.62`，分辨率/大小/非默认标签用 `"  ·  "` 连接；右侧锁图标。

**底栏**：渐变透明 → 0.55 → 0.9，`navigationBarsPadding`。

```
         ──────●──────────        ← Slider，主色 thumb/active track
 28:26                     1:53:13  ← 11.5sp
    ⏮      (▶)      ⏭   1/2       ← 44dp 圆底 + 52dp 主色圆形播放钮
         [1.0x]  [适应]             ← PillButton 胶囊
```

- 播放/暂停是 **52dp 主色实心圆**，比两侧 44dp 圆底大一圈，是视觉焦点。
- 倍速和填充是 `PillButton`：`RoundedCornerShape(50)`，激活态主色 0.16 底 + 主色文字，非激活白色 0.1 底。
- 倍速菜单当前项带 `AppIcons.Check`。

**常驻细进度条**：3dp，贴屏幕底部中央，主色。不占控制层，随时能看出播到哪。

**锁定态**：所有控件隐藏，只留 40dp 圆形黑 0.45 底 + `AppIcons.Lock`。

**加载/错误**：`surfaceContainer` 卡片 `RoundedCornerShape(20dp)`，主色 `CircularProgressIndicator` 34dp；错误卡片带 `AppIcons.Info` + error 色 + 胶囊按钮。

---

## 5. 一致性与约束

- **所有颜色走 `MaterialTheme.colorScheme`**，除播放器渐变和图片角标遮罩（需要纯黑透明度）。
- **所有图标走 `AppIcons`**，无 emoji、无 `material-icons-extended`。
- **不用描边控件**：搜索框、chip、按钮一律填充式。
- **默认深色**：`AppTheme(darkTheme: Boolean = true)`，忽略系统浅色设置 —— 影音应用不该被系统主题打断。
- **点击区 ≥ 44dp**：图标按钮统一 44dp 圆底，播放钮 52dp。

---

## 6. 改动的文件

| 文件 | 状态 |
|---|---|
| `ui/common/AppTheme.kt` | 新增，5.1 KB |
| `ui/common/AppIcons.kt` | 新增，7.8 KB |
| `ui/player/PlayerActivity.kt` | 1270 行，顶/底栏重写，emoji 图标清零 |
| `ui/wall/WallTab.kt` | 重写，30 KB |
| `ui/wall/MediaDetailSheet.kt` | 重写，16 KB |
| `ui/gallery/GalleryTab.kt` | 重写，9.7 KB |
| `ui/onboarding/OnboardingFlow.kt` | 渐变底色对齐新主题 |

构建验证：`assembleDebug` BUILD SUCCESSFUL；APK dex 内 `AppTheme`/`AppIcons`/`PillButton`/`PosterSkeletonGrid`/`ContinueCard`/`RecentTab` 全部存在，旧色 `#E53935` 计数 0，旧底 `#17212B` 清零。

---

## 7. 未做（下一轮）

- 收藏（本地标记 + 筛选 chip）
- 下载到本机（`Movies/TG云盘/` + 前台服务 + 串行队列）
- 排序记忆接线（`librarySort` / `librarySortDesc` 键已在 `SettingsStore`）
- 缩略图懒加载与内存缓存调优
- 分享链接 / 删除撤销（5s）
