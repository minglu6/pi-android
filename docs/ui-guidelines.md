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

禁止：裸 `AlertDialog.Builder`、未套 `PiMaterialAlertDialog` 的 `MaterialAlertDialogBuilder`、在 sheet 外另起一套圆角/字号。

## 空态

空态至少包含：**图标或 logo、标题、一句说明**。需要用户行动时再加：

- 主按钮（如「新建定时」「打开设置」）
- 可选示例 chip（填回 composer，不直接发送）

不要用「可点的假列表行」当空态文案。

## 字体

- Activity：`AppFonts.beforeContentView` → `setContentView` → `AppFonts.afterContentView`
- 任意 inflate 的 sheet / dialog root：立刻 `AppFonts.applyTree(root)`
- 列表 bind：`AppFonts.mono(ctx, bold)`；**不要**每次 `new Font.Builder`（OOM）
- 用户改「界面 / 终端字体」后 `recreate()`，主题走 `AppTheme` / `AppTheme.System`

## 新增功能检查清单

- [ ] 用现有 sheet / dialog 组件，而不是系统默认弹窗
- [ ] 空态完整（图标 + 标题 + 说明 [+ CTA]）
- [ ] `AppFonts.applyTree` 或 `mono()` 已覆盖
- [ ] 色与间距对齐 notice / picker（不是 Material 默认 lavender）
- [ ] 字符串进 `strings.xml`，中文用户可见文案不用「桥」而用「连接器」
