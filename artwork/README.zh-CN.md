# 图标素材

[English](README.md) | 简体中文

Pi 标志取自 [Pi Press Kit](https://pi.dev/press-kit) 发布的[上游 SVG](https://pi.dev/logo.svg)。本项目为非官方项目，与 Earendil Inc. 无关联，也未获其背书。本项目采用 MIT 许可证的代码与第三方品牌权利之间的区别，见[署名与品牌声明](../NOTICE.zh-CN.md)。

## 素材清单

下表中的路径均相对于本目录。

| 文件 | 用途 |
| --- | --- |
| [`logo.svg`](logo.svg) | 上游三色 SVG，视口为 800 × 800。 |
| [`../app/res/drawable/ic_pi_logo.xml`](../app/res/drawable/ic_pi_logo.xml) | 彩色 Android 矢量 drawable，用于应用界面和通知小图标。采用收紧后的 470 × 470 视口，源路径沿两个坐标轴各平移 −165.29。 |
| [`../app/res/drawable/ic_pi_logo_monochrome.xml`](../app/res/drawable/ic_pi_logo_monochrome.xml) | 对应的单色 drawable，采用相同的紧凑视口和平移后几何形状；与自适应启动器图标的单色图层相互独立。 |
| [`../app/res/drawable/ic_launcher_foreground.xml`](../app/res/drawable/ic_launcher_foreground.xml) | 108dp 自适应前景矢量，独立包含原始 SVG 路径，沿两个坐标轴均缩放 `0.106`、平移 `11.6`。可见标志在 108 × 108 视口中约占 29–79 的范围，位于中央 66dp 安全区域内。 |
| [`../app/res/drawable/ic_launcher_monochrome.xml`](../app/res/drawable/ic_launcher_monochrome.xml) | 独立的单色自适应矢量，采用相同变换。在支持主题化图标的环境中，由系统应用主题着色。 |
| [`../app/res/drawable/ic_launcher_background.xml`](../app/res/drawable/ic_launcher_background.xml) | 自适应背景：从 `#0B1016` 到 `#121820` 的 135° 线性渐变。 |
| [`../app/res/mipmap-anydpi-v26/ic_launcher.xml`](../app/res/mipmap-anydpi-v26/ic_launcher.xml) | 自适应图标定义，组合上述背景、前景和单色 drawable。 |
| [`../app/res/mipmap-anydpi-v26/ic_launcher_round.xml`](../app/res/mipmap-anydpi-v26/ic_launcher_round.xml) | 圆形自适应图标定义，使用相同图层。 |
| `../app/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` 和 `ic_launcher_round.png` | 标准与圆形图标在各密度下的传统 PNG 回退资源。 |

[`AndroidManifest.xml`](../app/AndroidManifest.xml) 的应用图标引用 `@mipmap/ic_launcher`，圆形图标引用 `@mipmap/ic_launcher_round`。自适应背景使用专用 drawable，而非共享的界面颜色 `@color/bg_base`。

## 替换图标素材

请使用你拥有必要使用权的素材。仅修改 `logo.svg` 不会改变 Android 资源；应同步更新矢量资源和传统 PNG 图标。

1. 将 `app/res/drawable/ic_pi_logo.xml` 和 `app/res/drawable/ic_pi_logo_monochrome.xml` 同时替换为新素材对应的 Android 矢量 drawable。保留资源名称以维持现有引用，并为应用内小尺寸显示选择合适的视口边界。
2. 替换 `ic_launcher_foreground.xml` 和 `ic_launcher_monochrome.xml` 中内嵌的路径。它们是独立的 108dp 矢量，并非包裹 `ic_pi_logo*.xml` 的内缩资源。根据新素材调整缩放和平移，使可见标志保持在中央安全区域内。
3. 检查 `ic_launcher_background.xml` 及 `app/res/mipmap-anydpi-v26/` 中的两个自适应图标定义，按需更新背景或图层引用。
4. 替换上述全部五个密度目录中的 `ic_launcher.png` 和 `ic_launcher_round.png`，使传统启动器也显示一致的品牌标识。
5. 如果更改资源名称，应同步更新自适应图标图层、清单、布局及 Java 引用。彩色 drawable 被 [`activity_main.xml`](../app/res/layout/activity_main.xml)、[`sheet_sessions.xml`](../app/res/layout/sheet_sessions.xml)、[`sheet_schedules.xml`](../app/res/layout/sheet_schedules.xml) 和 [`BridgeService.java`](../app/src/dev/pi/android/bridge/BridgeService.java) 引用。
6. 更新源素材及署名文档，使其与实际分发的素材保持一致。按照[构建指南](../docs/build-pipeline.zh-CN.md)生成包含替换后资源的 APK。

发布前，应在目标 Android 版本上检查标准与圆形启动器图标、传统回退图标、受支持环境中的主题化启动器图标、应用内素材及通知效果。资源替换说明不构成使用任何第三方名称或素材的许可。
