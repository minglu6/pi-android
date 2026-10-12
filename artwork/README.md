# Artwork

English | [简体中文](README.zh-CN.md)

The Pi logo is sourced from the [upstream SVG](https://pi.dev/logo.svg), published in the [Pi Press Kit](https://pi.dev/press-kit). This is an unofficial project, not affiliated with or endorsed by Earendil Inc. See the [attribution and brand notice](../NOTICE.md) for the distinction between the project's MIT-licensed code and third-party brand rights.

## Asset inventory

Paths below are relative to this directory.

| File | Purpose |
| --- | --- |
| [`logo.svg`](logo.svg) | Upstream three-color SVG with an 800 × 800 viewport. |
| [`../app/res/drawable/ic_pi_logo.xml`](../app/res/drawable/ic_pi_logo.xml) | Color Android vector drawable used in the application UI and as the notification small icon. Uses a tight 470 × 470 viewport, with the source paths translated by −165.29 on both axes. |
| [`../app/res/drawable/ic_pi_logo_monochrome.xml`](../app/res/drawable/ic_pi_logo_monochrome.xml) | Single-color companion drawable with the same tight viewport and translated geometry; separate from the adaptive launcher's monochrome layer. |
| [`../app/res/drawable/ic_launcher_foreground.xml`](../app/res/drawable/ic_launcher_foreground.xml) | 108dp adaptive foreground vector with its own copy of the original SVG paths, scaled by `0.106` and translated by `11.6` on both axes. The visible mark spans approximately 29–79 within the 108 × 108 viewport, inside the central 66dp safe zone. |
| [`../app/res/drawable/ic_launcher_monochrome.xml`](../app/res/drawable/ic_launcher_monochrome.xml) | Independent single-color adaptive vector using the same transform. The system applies themed-icon tint where supported. |
| [`../app/res/drawable/ic_launcher_background.xml`](../app/res/drawable/ic_launcher_background.xml) | Adaptive background: a 135° linear gradient from `#0B1016` to `#121820`. |
| [`../app/res/mipmap-anydpi-v26/ic_launcher.xml`](../app/res/mipmap-anydpi-v26/ic_launcher.xml) | Adaptive icon definition combining the background, foreground, and monochrome drawables above. |
| [`../app/res/mipmap-anydpi-v26/ic_launcher_round.xml`](../app/res/mipmap-anydpi-v26/ic_launcher_round.xml) | Round adaptive icon definition using the same layers. |
| `../app/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` and `ic_launcher_round.png` | Density-specific legacy PNG fallbacks for the standard and round icons. |

[`AndroidManifest.xml`](../app/AndroidManifest.xml) uses `@mipmap/ic_launcher` for the application icon and `@mipmap/ic_launcher_round` for the round icon. The adaptive background is a dedicated drawable, not the shared `@color/bg_base` UI color.

## Replacing the artwork

Use artwork you have the necessary rights to use. Editing `logo.svg` alone does not change the Android resources; update the vector resources and legacy PNGs together.

1. Replace both `app/res/drawable/ic_pi_logo.xml` and `app/res/drawable/ic_pi_logo_monochrome.xml` with Android vector drawables for the new artwork. Keep the resource names to preserve existing references, and choose viewport bounds appropriate for small in-app rendering.
2. Replace the embedded paths in `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml`. These are standalone 108dp vectors, not inset wrappers around `ic_pi_logo*.xml`. Adjust their scale and translation for the new artwork while keeping the visible mark within the central safe zone.
3. Review `ic_launcher_background.xml` and both adaptive definitions in `app/res/mipmap-anydpi-v26/`. Update the background or layer references as needed.
4. Replace `ic_launcher.png` and `ic_launcher_round.png` in all five density directories listed above so that legacy launchers display the same branding.
5. If renaming resources, update the adaptive layers, manifest, layouts, and Java references together. The color drawable is referenced by [`activity_main.xml`](../app/res/layout/activity_main.xml), [`sheet_sessions.xml`](../app/res/layout/sheet_sessions.xml), [`sheet_schedules.xml`](../app/res/layout/sheet_schedules.xml), and [`BridgeService.java`](../app/src/dev/pi/android/bridge/BridgeService.java).
6. Update the source artwork and attribution documentation to match the assets you distribute. Follow the [build guide](../docs/build-pipeline.md) to produce an APK with the replacement resources.

For a release, review the standard and round launcher icons, legacy fallbacks, themed launcher icon where supported, in-app artwork, and notifications on the intended Android versions. Resource replacement instructions are not a grant of permission to use any third-party name or artwork.
