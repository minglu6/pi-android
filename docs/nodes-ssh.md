# 节点（Tailscale）与 SSH 终端

手机通过 Tailscale MagicDNS / `100.x` 连其它机器上的 Pi 连接器，或打开应用内 SSH 终端。

## 每台节点要做的事

1. **安装并登录 Tailscale**（手机与目标机同一 tailnet）。
2. **在目标机启动连接器，绑定 Tailscale 接口**（不要绑 `0.0.0.0`）：

   ```sh
   # 解析本机 Tailscale IPv4 并只听该地址
   node bridge/pi-rpc-bridge.mjs --host tailscale --port 8945
   # 或显式：
   node bridge/pi-rpc-bridge.mjs --host $(tailscale ip -4) --port 8945
   ```

3. **Token**：目标机 `~/.pi/agent/pi-android.token`（权限 600）与 App 里该节点的 token 一致。
4. **App → 设置 → 节点与 Tailscale**：添加节点（MagicDNS 名或 `100.x`、连接器端口、token）；
   或「导入 tailscale status」粘贴 `tailscale status --json`。
5. **点节点 → 聊天**：切换当前连接器并重连。
6. **SSH 终端**（可选）：节点编辑页生成 Ed25519 密钥，把公钥写入目标机
   `~/.ssh/authorized_keys`，再点「终端」。主机密钥首次连接 TOFU 确认。

本机 Termux 仍可用默认 `--host 127.0.0.1`（或省略）；远程节点必须用 `--host tailscale`
或显式 `100.x`。

## 安全

- 连接器仍靠 **token** 鉴权；`--host tailscale` 只扩大到 tailnet，不是公网。
- SSH 密码 / 私钥 / token 存 Android Keystore（AES-GCM），不进节点 JSON。
- 勿把连接器绑到公网网卡。

## 终端字体

默认 **MesloLGLDZ Nerd Font Mono**（Meslo LG Large、点状 0、Nerd 补丁含 powerline /
图标字形）。设置里可改「系统等宽」与字号；终端内支持捏合缩放。CJK 缺字走系统
sans-serif 回退（API 29+）。许可：Meslo Apache-2.0 + Nerd Fonts MIT，见
`app/res/raw/meslo_apache_license.txt` 与 `nerd_fonts_mit.txt`。
