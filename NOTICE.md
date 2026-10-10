# NOTICE — 商标与 logo

## Pi 的 logo

`app/res/drawable/ic_pi_logo.xml` 是 Pi 官方 logo 的 vector 版本，取自 https://pi.dev/logo.svg
（三条路径与 `#F09082 / #4D9ABF / #F1BE58` 三个色值与上游一致），用作本 App 的图标。
Monochrome / adaptive 变体见 `artwork/README.md` 与
`app/res/drawable/ic_pi_logo_monochrome.xml`。

pi.dev 的 [Press Kit](https://pi.dev/press-kit) 把它描述为「Vector logo for light/dark usage and
**editorial placements**」，并未明确允许第三方把它当作独立 App 的品牌标识。因此：

- **个人自用 APK**（本机安装，不分发）：属于风险最低的用法，但**不等于获得品牌授权**；
- **公开发布 / 分发**：正式前应先向 Press Kit 里的联系渠道 `rfc@earendil.com` 申请许可；
- 想彻底免事就换图标：`docs/build-pipeline.md` 里提到资源如何替换，把
  `res/drawable/ic_pi_logo.xml` 与 `res/mipmap-anydpi-v26/ic_launcher.xml` 换成自己的素材即可。

## 名字与关联

项目名 `pi-android` 只是描述「Android 上的 Pi 客户端」。“Pi” 与 Pi 的 logo 是对应权利方的商标。
本仓库**不是**官方项目：README 与 UI 里都不得暗示官方背书；如需开源发布，README 首屏保留
“Unofficial Android client … Not affiliated with or endorsed by Earendil Inc.” 的表述。

## 许可证分离

- 本仓库自己的代码：MIT（见 `LICENSE`）。
- Pi 的 logo / 商标：**不在** MIT 授权范围内 —— MIT 授权的是代码，不等同于商标与品牌素材授权。
- 依赖的 AndroidX / Material / OkHttp 等：各自 Apache-2.0，sha256 与来源见 `app/libs/manifest.json`。
- 本项目不 fork、不 vendored、不 patch Pi 源码；只通过 `pi --mode rpc` 这一官方支持的
  JSONL 子协议驱动已安装的 Pi。
