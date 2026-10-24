# Attribution and brand notice

English | [简体中文](NOTICE.zh-CN.md)

## Project identity

**pi-android is an unofficial Android client for Pi. It is not affiliated with or endorsed by Earendil Inc.**

The project name describes its purpose as an Android client for Pi. The Pi name and logo belong to their respective rights holders. Project documentation, application branding, and distribution materials must not imply that this is an official Pi application or that it has upstream endorsement.

The bridge communicates with an installed Pi CLI through `pi --mode rpc`, using JSONL over standard input and output. This repository does not fork, vendor, or patch Pi source code. This integration does not imply an affiliation with the upstream project.

## Pi logo attribution

The logo in this repository comes from the [Pi logo SVG](https://pi.dev/logo.svg), published in the [Pi Press Kit](https://pi.dev/press-kit).

- [`artwork/logo.svg`](artwork/logo.svg) contains the upstream three-path, 800 × 800 vector artwork.
- [`app/res/drawable/ic_pi_logo.xml`](app/res/drawable/ic_pi_logo.xml) adapts the artwork to an Android vector drawable with a tight 470 × 470 viewport. Its paths are translated by −165.29 on both axes, retaining the logo's shape and the colors `#F09082`, `#4D9ABF`, and `#F1BE58`.
- [`app/res/drawable/ic_pi_logo_monochrome.xml`](app/res/drawable/ic_pi_logo_monochrome.xml) is the corresponding single-color drawable. The adaptive launcher's color and monochrome layers contain their own scaled paths, rather than referencing these in-app drawables.

See the [artwork guide](artwork/README.md) for the adaptive icon layers, legacy PNGs, resource references, and replacement instructions.

## Brand permissions

The Pi Press Kit describes the logo as a “Vector logo for light/dark usage and editorial placements.” That description does not explicitly grant permission to use the logo as the brand identity of an independent application. This repository does not establish that such permission has been granted.

Personal installation does not itself grant brand rights. Before public release or distribution with Pi branding, seek permission or clarification through the Press Kit contact, [rfc@earendil.com](mailto:rfc@earendil.com). Alternatively, replace the Pi artwork with assets you have the necessary rights to use, following the [artwork guide](artwork/README.md). Replacing an icon alone does not establish compliance for all names, branding, or distribution materials.

## UI acknowledgments

The native Android UI takes visual inspiration from the Tokyo Night styling of [Pi Pocket](https://github.com/TannerMidd/pi-pocket). Its mobile interaction patterns also informed the composer and transcript presentation; the [mobile interaction notes](docs/mobile-interaction.md) identify the reference revision and implementation boundaries. This acknowledgment does not imply affiliation with or endorsement by Pi Pocket.

## License boundaries

- The project's own code is provided under the MIT License; see [`LICENSE`](LICENSE).
- The project's MIT License does not grant rights to the Pi name, logo, trademarks, or other third-party brand assets.
- Third-party dependencies, including AndroidX, Material Components, and OkHttp, remain subject to their own licenses and notices. Review the licenses of the resolved artifacts before redistribution; the project's MIT License is not a substitute for them.
- [`app/tools/fetch-aars.py`](app/tools/fetch-aars.py) generates `app/libs/manifest.json` when fetching dependencies. That generated file records artifact coordinates and SHA-256 checksums; it is not a license or brand-permission record.

## Bundled font attribution

The application bundles **MesloLGLDZ Nerd Font Mono**, Regular and Bold, in `app/res/font/meslo_lgldz_nf_*.ttf`. These are the Meslo LG Large, Dotted Zero, Mono variants from `Meslo.zip` in [Nerd Fonts v3.3.0](https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.3.0).

- Upstream Meslo: Copyright 2009, 2010, 2013 André Berg, licensed under Apache-2.0. See the bundled [copyright and license notice](app/res/raw/meslo_apache_license.txt) and [full Apache License 2.0](app/res/raw/meslo_apache_2_0_full.txt).
- Nerd Fonts patching code: Copyright (c) 2014 Ryan L McIntyre, licensed under MIT. See the bundled [Nerd Fonts MIT License](app/res/raw/nerd_fonts_mit.txt).
- The bundled [font provenance notice](app/res/raw/meslo_nerd_notice.txt) records the source and local glyph subsetting; CJK text falls back to system fonts.

Retain the applicable font license and attribution notices when redistributing these assets. The project's MIT License does not replace their licensing terms.

## Provider icons

The `app/res/drawable/ic_provider_*.xml` assets adapt monochrome SVGs from [Lobe Icons](https://github.com/lobehub/lobe-icons), Copyright (c) 2023 LobeHub, under the [bundled MIT License](app/res/raw/lobe_icons_mit.txt). Each drawable records its upstream source URL. They identify the selected provider only; trademarks remain with their respective owners and do not imply endorsement.
