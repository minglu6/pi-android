# 署名与品牌声明

[English](NOTICE.md) | 简体中文

## 项目身份

**pi-android 是非官方的 Pi Android 客户端，与 Earendil Inc. 无关联，也未获其背书。**

项目名称用于描述其作为 Pi Android 客户端的用途。Pi 名称和标志归相应权利方所有。项目文档、应用品牌标识及分发材料不得暗示本项目是 Pi 官方应用或获得了上游背书。

桥接服务通过 `pi --mode rpc` 与已安装的 Pi CLI 通信，在标准输入和标准输出上交换 JSONL 消息。本仓库不 fork、不内置副本，也不修改 Pi 源码。采用这一集成方式并不意味着本项目与上游存在关联。

## Pi 标志来源

本仓库中的标志取自 [Pi 标志 SVG](https://pi.dev/logo.svg)，该素材发布于 [Pi Press Kit](https://pi.dev/press-kit)。

- [`artwork/logo.svg`](artwork/logo.svg) 保存上游由三条路径组成、视口为 800 × 800 的矢量素材。
- [`app/res/drawable/ic_pi_logo.xml`](app/res/drawable/ic_pi_logo.xml) 将该素材转换为 Android 矢量 drawable，收紧为 470 × 470 的视口。路径沿两个坐标轴各平移 −165.29，保留标志形状及 `#F09082`、`#4D9ABF`、`#F1BE58` 三个颜色值。
- [`app/res/drawable/ic_pi_logo_monochrome.xml`](app/res/drawable/ic_pi_logo_monochrome.xml) 是对应的单色 drawable。自适应启动器图标的彩色和单色图层各自包含缩放后的路径，并不引用这两个应用内 drawable。

自适应图标图层、传统 PNG 图标、资源引用和替换方法见[图标素材指南](artwork/README.zh-CN.md)。

## 品牌使用许可

Pi Press Kit 将该标志描述为“Vector logo for light/dark usage and editorial placements”（用于浅色、深色背景及编辑内容配图的矢量标志）。这一描述并未明确授予将该标志用作独立应用品牌标识的许可。本仓库不构成已获得此类许可的证明。

个人安装本身并不会授予品牌使用权。在保留 Pi 品牌标识公开发布或分发之前，应通过 Press Kit 所列联系人 [rfc@earendil.com](mailto:rfc@earendil.com) 申请许可或确认使用范围。也可以按照[图标素材指南](artwork/README.zh-CN.md)，将 Pi 素材替换为你拥有必要使用权的素材。仅替换图标并不能证明所有名称、品牌标识或分发材料均符合相关要求。

## 界面设计致谢

原生 Android 界面的视觉设计借鉴了 [Pi Pocket](https://github.com/TannerMidd/pi-pocket) 的 Tokyo Night 风格，其移动端交互模式也为输入区和对话呈现提供了参考。[移动端交互说明](docs/mobile-interaction.md) 记录了参考版本和实现边界。这一致谢不意味着本项目与 Pi Pocket 存在关联或获得其背书。

## 许可证边界

- 本项目自身的代码采用 MIT 许可证，见 [`LICENSE`](LICENSE)。
- 本项目的 MIT 许可证不授予 Pi 名称、标志、商标或其他第三方品牌素材的使用权。
- AndroidX、Material Components、OkHttp 等第三方依赖仍受各自的许可证及声明约束。再次分发前，应审查实际解析得到的依赖构件所附许可证；本项目的 MIT 许可证不能替代这些许可证。
- [`app/tools/fetch-aars.py`](app/tools/fetch-aars.py) 在获取依赖时生成 `app/libs/manifest.json`。该生成文件记录构件坐标和 SHA-256 校验值，不是许可证或品牌授权记录。

## 内置字体署名

应用在 `app/res/font/meslo_lgldz_nf_*.ttf` 中内置 **MesloLGLDZ Nerd Font Mono** 的常规和粗体字重。这些字体取自 [Nerd Fonts v3.3.0](https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.3.0) 的 `Meslo.zip`，具体为 Meslo LG Large、Dotted Zero、Mono 变体。

- 上游 Meslo：Copyright 2009, 2010, 2013 André Berg，采用 Apache-2.0 许可证。见随附的[版权及许可声明](app/res/raw/meslo_apache_license.txt)和 [Apache License 2.0 全文](app/res/raw/meslo_apache_2_0_full.txt)。
- Nerd Fonts 补丁代码：Copyright (c) 2014 Ryan L McIntyre，采用 MIT 许可证。见随附的 [Nerd Fonts MIT 许可证](app/res/raw/nerd_fonts_mit.txt)。
- 随附的[字体来源声明](app/res/raw/meslo_nerd_notice.txt)记录了来源及本地字形子集化处理；中日韩文字回退到系统字体。

再次分发这些素材时，应保留适用的字体许可证和署名声明。本项目的 MIT 许可证不能替代其许可条款。
