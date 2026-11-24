# HDRI Sphere

An open-source Android app for capturing **HDR environment maps** (360°×180° equirectangular `.hdr` / `.exr`) with your phone, for use as sky and image-based lighting in Godot, Unity, Unreal, Blender and others.

> Status: early development. See the roadmap below.

## How it works

1. The app picks the widest camera that supports RAW + manual exposure. On phones such as the Pixel, that's usually the ultra-wide lens. You can also choose a lens yourself.
2. Aim spots covering the whole sphere are drawn over the live camera view. Point the phone at each spot and hold still; the app captures an exposure bracket automatically.
3. Brackets are captured as linear RAW. Brackets are extended toward shorter exposures until highlights are no longer clipped, so bright skies and lamps keep their real intensity.
4. On the phone, the brackets are merged to radiance, aligned (gyro pose refined with feature matching), and stitched into an equirectangular HDR. Output is up to 8K `.hdr` and `.exr`, plus a tone-mapped JPEG preview.
5. You can also export the raw set (DNGs + `session.json` + a Hugin `.pto`) and stitch it on a desktop.

## Repository layout

| Path | Contents |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose, Camera2) |
| `core/` | Portable C++17 HDR/stitching core, built for Android (NDK) and desktop |
| `tools/cli/` | Desktop CLI that runs the same core on exported sessions |
| `docs/` | Capture guide, engine import notes |

## Building

Requirements: JDK 21, Android SDK with platform 37, NDK 29.0.14206865.

```sh
./gradlew :app:assembleDebug          # Android app
cmake -S core -B core/build-desktop -G Ninja && cmake --build core/build-desktop
ctest --test-dir core/build-desktop   # native core tests
```

## Roadmap

- [x] Project skeleton, native core (RGBE writer, bracket merge), CI
- [x] Camera: lens enumeration, manual-exposure RAW bursts, adaptive brackets, DNG storage *(needs on-device testing)*
- [x] Capture UX: orientation tracking, aim-spot overlay, auto-trigger, raw-set export + Hugin `.pto` *(needs on-device testing)*
- [ ] Native core: RAW decode, desktop CLI
- [ ] Stitching: pose refinement, equirect warp, seams, float multi-band blend, nadir fill, EXR
- [ ] Sun energy estimation, settings screen, docs, F-Droid

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
