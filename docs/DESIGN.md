# TG云盘 · 设计语言（Design Language）

> 基底：深色影院风（近黑蓝底 + 单一高饱和蓝主色）；配套完整浅色。所有颜色一律走
> `MaterialTheme.colorScheme.*` 语义色，**禁止**在 Composable 里写死 `Color(0x...)`。

## 1. 颜色（语义 token，勿硬编码）
优先用 MaterialTheme 语义色，按用途取：

| 用途 | 取色 | 深色示例 | 浅色示例 |
|---|---|---|---|
| 页面背景 | `colorScheme.background` | #0A0D12 | #F5F7FA |
| 卡片/面板 | `colorScheme.surface` / `surfaceContainer*` | #10151C / #141A23 | #FFFFFF / #EFF3F8 |
| 主文字 | `colorScheme.onSurface` | #E9EEF5 | #12161B |
| 次文字 | `colorScheme.onSurfaceVariant` | #94A4B6 | #4A5663 |
| 强调/选中 | `colorScheme.primary` | #4EA1FF | #2C6FBF |
| 描边/分隔 | `colorScheme.outline` / `outlineVariant` | #3A4553 / #26303D | #B6C0CB / #D5DDE6 |
| 危险 | `colorScheme.error` | #FF6B6B | #C0392B |

**硬编码色替换规则**（Wave2 全量执行）：
- `Color.Black` / `Color(0xFF0A0D12..)` 背景 → `background` 或 `surface`
- `Color.White` / `Color(0xFFE9EEF5..)` 文字 → `onSurface`（次要→`onSurfaceVariant`）
- 半透明黑遮罩（图片/视频角标、播放器控件底）→ 保留黑色半透明（媒体上叠层不随主题变），
  这是**唯一允许**硬编码黑的地方（`Color.Black.copy(alpha=..)` 叠在媒体之上）。
- 品牌蓝常量统一走 `colorScheme.primary`，不再直接 `Color(0xFF4EA1FF)`。

## 2. 间距栅格（4dp 基准）
`4 / 8 / 12 / 16 / 20 / 24 / 32`。页面左右内边距 **16dp**；卡片内边距 **16dp**；
列表项间距 **8–12dp**；区块间距 **16dp**。

## 3. 圆角（用 `MaterialTheme.shapes`）
- `extraSmall 6` 小标签/角标
- `small 10` 输入框/小按钮
- `medium 14` 卡片/单元格
- `large 20` 大卡片/底部弹层
- `extraLarge 28` 对话框/全屏容器
海报/缩略图单元格圆角 **10–12dp**；按钮圆角用 `small`。

## 4. 字阶（用 `MaterialTheme.typography`）
标题用 `headlineSmall/titleLarge`；正文 `bodyLarge/bodyMedium`；辅助 `bodySmall`；
标签/按钮 `labelLarge`。**不要**手写 `fontSize`/`fontWeight`，除非用于纯媒体叠层文本。

## 5. 图标
统一用 `com.yg.tgdrive.ui.common.AppIcons`（`Play/Movie/Image/Close/ArrowBack/...`）。
新增图标加在 AppIcons 里，尺寸：工具栏 22–24dp，行内 18–20dp，角标 11–13dp。

## 6. 动效
- 按压：`Modifier.clickable` 默认水波或用 `animateFloatAsState` 做 0.96 缩放。
- 转场：页面切换用 `AnimatedContent`/`Crossfade`，200ms 左右，`FastOutSlowIn`。
- 加载：网格用骨架屏（已有），不要白屏抖动。

## 7. 明暗两态检查清单（每个屏幕交付前自检）
1. 背景/卡片/文字三者在深+浅两色下都对比清晰（AA）。
2. 无残留写死深色背景导致的“浅色下黑块”。
3. 图片/视频角标、播放器控件（媒体叠层）两色下都可读（通常固定黑半透明 + 白字）。
4. 选中态（primary）在两种背景都醒目。
5. 播放页跟随主题：浅色下播放壳用浅色 surface，控件底可仍用黑色半透明保证对比。

## 8. 播放页
- 跟随主题（用户已确认）：浅色主题下页面壳用浅色；但播放控件条/按钮底仍建议
  黑色半透明以在任何画面上可读。
