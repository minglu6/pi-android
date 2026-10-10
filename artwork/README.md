# artwork

Pi 官方 logo 素材（取自 https://pi.dev/logo.svg）。商标约束见仓库根目录 `NOTICE.md`。

| 文件 | 用途 |
| --- | --- |
| `logo.svg` | 上游三色矢量原件 |
| `../app/res/drawable/ic_pi_logo.xml` | 应用内 / 通知 / adaptive foreground 用的彩色 vector |
| `../app/res/drawable/ic_pi_logo_monochrome.xml` | Material You monochrome 层（单色，由系统 tint） |
| `../app/res/drawable/ic_launcher_foreground.xml` | adaptive icon 前景（18% inset 安全区） |
| `../app/res/drawable/ic_launcher_monochrome.xml` | adaptive icon monochrome（同 inset） |
| `../app/res/mipmap-anydpi-v26/ic_launcher.xml` | adaptive icon 入口（minSdk 26，无需密度 PNG 回退） |

替换图标时：改 `ic_pi_logo*.xml` 与 `mipmap-anydpi-v26/ic_launcher.xml` 即可，见 `docs/build-pipeline.md`。
