# Pi Android UI 指南

短规范，保证新功能跟现有聊天壳一致，避免再出现「系统 Dialog + 默认字体」拼贴感。

## 视觉词汇

| Token | 用途 |
|---|---|
| Tokyo Night 色板（`colors.xml` / `values-night`） | 背景、正文、强调色 |
| MesloLGLDZ Nerd + CJK fallback（`AppFonts` / `@font/meslo_lgldz_nf`） | **全部**界面文字；设置可切系统等宽 |
| `bg_elevated` + 顶角 28dp | Bottom sheet 壳 |
| 拖拽条 32×4 + 标题 18sp bold + 左右 20dp | Sheet 头 |
| `bg_search_field` / 12dp 圆角 | 输入框、搜索框 |
| `bg_notice_card` / `bg_approval_card` + 3dp 色条 | 状态条、提示卡 |
| `bg_notice_action` / `bg_suggest_chip` | 次级操作、示例 chip |
| 页脚主操作 44dp、蓝色字 | Sheet 底部 CTA |

## 组件怎么选

1. **列表选择 / 搜索** → `PickerSheet`（模型、思考、会话动作、输入历史、cwd…）
2. **多行编辑** → `EditorSheet`（会话说明等）；禁止用 `MaterialDialogs.promptText` 做多行
3. **定时消息** → `SchedulesSheet`（空态 = logo + 标题 + 说明 + 主 CTA + 示例 chip）
4. **查找** → `FindSheet`
5. **会话历史** → `SessionsSheet`
6. **短确认 / 单行路径输入** → `MaterialDialogs`（已套 `PiMaterialAlertDialog` + `AppFonts.applyTree`）
7. **聊天内系统提示** → notice card 布局，不要塞进 `AlertDialog`
8. **连接器进度 / Termux 前置配置** → `ConnectorProgressSheet` / `TermuxSetupSheet`
9. **连接配置（多主机 / Tailscale）** → `ProfilesSheet` / `ProfileEditorSheet`

禁止：裸 `AlertDialog.Builder`、未套 `PiMaterialAlertDialog` 的 `MaterialAlertDialogBuilder`、在 sheet 外另起一套圆角/字号。

## 不要写多余说明

对**约定俗成**的交互，不要加图例 / 操作说明字幕：

- 状态点颜色（绿/红/灰）、下拉刷新、点选列表填回、拖拽条关 sheet 等 —— 用户已懂
- Sheet 标题下不要再叠一句「下拉刷新探测 · 绿=可达…」这类图例
- 输入框已有 `hint` 时，不要在标题下再重复同一段说明

仍该保留的：空态说明、一次性前置配置、错误/风险提示、字段 placeholder（只出现一次）。

## 空态

- **未连接**：只显示 logo（不要标题 / 说明 / 假 hint）。重连走横幅或设置，不在空态堆文案。
- **已连接、无消息**：logo + 短标题（如「今天想聊点什么？」）+ 最多 3 条**可行动**建议 chip（来自 `get_suggestions`：未完成 todo、中断会话、until/定时、未提交改动等）。点 chip = 发送对应 prompt（有 `sessionPath` 则先切会话再继续）。
- **无历史建议时**：cwd 感知兜底 chip（「看看这个项目…」；若是 git 仓库再加 git status）。
- **缺 Token**：标题 + 一句说明 +「打开设置」（这是前置配置，不是约定交互图例）。

不要用静态假建议（「解释这段代码」一类）占位；不要用「可点的假列表行」当空态文案。

## 字体

- Activity：`AppFonts.beforeContentView` → `setContentView` → `AppFonts.afterContentView`
- 任意 inflate 的 sheet / dialog root：立刻 `AppFonts.applyTree(root)`
- 列表 bind：`AppFonts.mono(ctx, bold)`；**不要**每次 `new Font.Builder`（OOM）
- 用户改「界面 / 终端字体」后 `recreate()`，主题走 `AppTheme` / `AppTheme.System`

## 新增功能检查清单

- [ ] 用现有 sheet / dialog 组件，而不是系统默认弹窗
- [ ] 空态完整（图标 + 标题 + 说明 [+ CTA]）
- [ ] 没有给约定交互加多余图例 / 重复 hint 字幕
- [ ] `AppFonts.applyTree` 或 `mono()` 已覆盖
- [ ] 色与间距对齐 notice / picker（不是 Material 默认 lavender）
- [ ] 字符串进 `strings.xml`，中文用户可见文案不用「桥」而用「连接器」
