# 移动端流式与工具呈现

## 参考与边界

本轮按用户指定参考 [TannerMidd/pi-pocket](https://github.com/TannerMidd/pi-pocket)，
审阅版本 `45685e1`，主要对应 `web/composer.js`、`web/transcript.js`、`web/chat.js` 和移动端 CSS。
采用其移动端交互思路，不引入 WebView/React，不替换本项目原生 Java、桥协议或已修好的模型/会话状态逻辑。

- 正文与工具活动分离，不把 assistant 的 `toolCall` 内容再次渲染成正文。
- 工具命令与原始输出分离；紧凑预览、展开/收起、横向查看长行、复制完整输出。
- 清楚区分生成等待、正文输出和工具执行，不把“还在思考/等工具”显示成无反馈。
- 手机软键盘 Enter 换行；实体键盘 Enter 提交，Shift+Enter 换行，组合输入/生成中不误提交或取消。
- 上翻阅读时不自动抢回底部；判断真实内容末尾，而非仅判断末条消息是否可见。
- 缓存模型身份不冒充当前连接的权威状态；完整 `get_state` 到达前标示同步中。

## 流式实现

`ui/TranscriptReducer.java` 是可独立 JVM 测试的展示 reducer：

- `text_delta` 按 `contentIndex` 排序累积，`text_end` 与 `message_end` 采用权威全文。
- `tool_execution_update.partialResult` 是最新的累积结果，必须**替换**，不能重复追加。
- 工具以 `toolCallId` 定位；保存名称、命令、未裁剪输出、状态和可用的耗时。
- 纯工具调用/思考块不制造空的助手气泡；工具结果不走 Markdown 渲染。
- 历史恢复根据 assistant 的 toolCall 与 toolResult 配对，恢复相同工具卡片和命令。
- 错误只显示一次；终止后不残留流式游标或运行中状态。

`MainActivity` 在 UI 线程合并 50ms 内的高频更新，终态立即刷新；关闭 RecyclerView change 动画。
预览截断属于控件展示，不截断 reducer 中保留的 RPC 输出。仍尊重 Pi 工具自身的输出限额：
若后端已裁剪并提供完整输出文件路径，客户端不会虚构已经取到该文件的完整内容。

## 验证与可重现取证

### 无凭据测试

```sh
bash tests/transcript/run.sh
bash tests/model-state/run.sh
bash tests/bridge-connection/run.sh
bash tests/extension-ui/run.sh
python3 tests/test_build_failures.py
node --test bridge/tests/pi-rpc-bridge.test.mjs tests/paced-fixture.test.mjs
```

`tests/ui-fixture.mjs` 不启动 Pi、不读取真实凭据/历史、不执行 shell。默认快节奏兼容原回归；
发送 `PACED_STREAM` 时分段显示正文，然后间隔显示累积 bash 输出。
支持进行中的连接重建、取消并保留已收到的工具输出。

```sh
node tests/ui-fixture.mjs --port 18945
# 仅调试 APK：连接 18945，token pi-ui-fixture-only，prompt PACED_STREAM。
# 结束后须恢复原连接；不得把 fixture 配置留作生产配置。
```

### 修前与真实后端的已确认事实

本机取证在虚拟屏进行，未操作主屏。私人截图不入库，位于 `~/tmp/pi-android-debug/`：

- `before-text-live.png`：正文已能在 message_end 前逐段显示，并非所有流式事件都丢失。
- `before-tool-live.png`：bash 运行中只有参数占位，无中间输出，还有重复 assistant → bash 气泡。
- `before-paced-final.png`：工具结束后才出现大块输出，长路径自动折行，原始文本被当 Markdown。

另开无持久会话、无工具/扩展/上下文文件的真实 Pi RPC 进程测得：
`opencode-go/hy4-preview`，166 个 text_delta，首段 6652ms，末段15582ms，跨度8930ms，
message_end 15675ms，stopReason=stop，stderr 0字节。
这验证真实后端会发增量；不改变用户生产会话，不把测试模型选择写入生产默认配置。

### 构建与代码回归

- 完整 APK 构建（aapt2 → javac → d8 → assemble → apksigner）通过。
- TranscriptTest：56 个断言；ModelStateTest：47 个检查；BridgeConnection：15 个回归。
- ExtensionUi 的 timeout/重复/重连/历史/状态回归通过；构建失败传播测试 2 个通过。
- Node：17 个真实桥协议测试 + 1 个 paced fixture 测试通过，0 失败。
- 工具字体沿用本机 Termux 的 MesloLGLDZ Nerd Font Regular 3.5.1，字体文件未改动。

同一会话的历史刷新/重连保留工具展开选择，仅确认切换会话或清空会话后重置。
修后设备回归以 `fresh-after-*` 抓帧为准；普通 `after-*` 抓帧碰到虚拟屏 OFF，
已判为无效证据，不用于宣称 UI 通过。

### 虚拟屏设备结果

- 正文首帧只有片段 01，工具运行中先出现 PARTIAL_1/2，再出现 PARTIAL_1…6；
  当时均未收到结束事件，底部仍为停止按钮/运行阶段，证明不是结束后一次性回放。
- 工具调用不再单独生成带 Pi 头像的重复正文；历史中的每个真实调用只保留一张卡片。
- 点击展开可见完整命令、字面的 Markdown 符号和 FINAL_TAIL；点手动重连后，
  重新读取 canonical history，卡片仍为“收起”状态，完整尾部未丢失。
- 浅色、深色及长路径横向滚动抓帧通过。每组重建虚拟屏，并在 state ON 时验证。
- 所有这些请求均使用本地 fixture 和假 token，不启动 Pi、不修改真实模型与会话。
  测试结束恢复修前全部 shared_prefs，按字节验证相同。

物理键盘/中文 IME 候选提交的组合行为未接外设复测；复制按钮经过源代码与完整输出
数据回归检查，但本轮未读取系统剪贴板做端到端断言。自动重连不在本轮新增范围，
上述连接验证使用现有“重新连接”按钮。
