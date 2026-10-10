# artwork

Pi 官方 logo 素材（取自 https://pi.dev/logo.svg）。商标约束见仓库根目录 `NOTICE.md`。

| 文件 | 用途 |
| --- | --- |
| `logo.svg` | 上游三色矢量原件 |
| `../app/res/drawable/ic_pi_logo.xml` | 应用内工具栏 / 空状态用的彩色 vector（内容包围盒视口） |
| `../app/res/drawable/ic_pi_logo_monochrome.xml` | 单色版（系统 tint） |
| `../app/res/drawable/ic_launcher_foreground.xml` | adaptive icon 前景（108dp，logo 落在 66dp 安全区） |
| `../app/res/drawable/ic_launcher_monochrome.xml` | adaptive monochrome 层 |
| `../app/res/drawable/ic_launcher_background.xml` | adaptive 背景渐变 |
| `../app/res/mipmap-anydpi-v26/ic_launcher.xml` | adaptive icon |
| `../app/res/mipmap-anydpi-v26/ic_launcher_round.xml` | adaptive round icon |
| `../app/res/mipmap-*/ic_launcher*.png` | 各密度 legacy 回退 PNG |

替换图标时：改 `ic_pi_logo*.xml` / `ic_launcher_*.xml` 与 mipmap，见 `docs/build-pipeline.md`。
