<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="branding/luma-dial-source/svg/logo-horizontal.svg">
    <img alt="Picture Tuner" src="branding/luma-dial-source/svg/logo-horizontal-dark.svg" width="480">
  </picture>
</p>

<p align="center">Calibrate your TV's HDR10, Dolby Vision and SDR picture with your eyes and the remote.</p>

Picture Tuner is an open-source Android TV / Google TV app. Each test screen asks one simple question, such as
"What is the lowest-numbered logo you can see?", and then tells you which setting to change, using your TV's
menu names (LG, Samsung and Sony; generic names for other brands).

There are two modes:
- **Guided calibration** walks through the essential screens for one format.
- **Free choice** lets you open any screen from the list.

Download the APK from [Releases](https://github.com/ivalkenburg/picture-tuner/releases) and install it on your
Android TV or Google TV device, for example with `adb install`.

## Controls

| Key | On a test screen | On a card |
|---|---|---|
| OK | Answer (finder screens: use the current step) | Select |
| BACK | Show the explanation | Go back (asks before leaving the guide) |
| ▲ ▼ | Step through levels, colors or sizes | Choose; scroll long text |
| ◀ ▶ | Previous / next test (guide: step) | Previous / next test on the explanation card |
| 1–4, 0 | Answer logo questions directly (0 = none) | Same, on the question card |
| CH+ / CH−, Page Up/Down | Jump 5 steps | |
| INFO / MENU | Show the explanation | |

Game controllers work too (A = OK, B = BACK). The volume keys always reach the TV.

## Test screens

| Screen | HDR10 | DV | SDR | Guided | What it checks |
|---|:-:|:-:|:-:|:-:|---|
| Black level | ✓ | ✓ | ✓ | ✓ | Logo 1 below black (invisible), logo 2 at 0.01 nit (barely visible) |
| Bright highlights | ✓ | ✓ | | ✓ | 400/600/800/900-nit logos in 1,000-nit squares |
| Very bright highlights | ✓ | ✓ | | ✓ | 1,000–3,600-nit logos in 4,000-nit squares (tone mapping) |
| Bright whites | | | ✓ | ✓ | SDR white clipping (levels 200–230 in 235) |
| Peak brightness finder | ✓ | | | ✓ | Logo levels in a 10,000-nit 10% window; gives a value for game HDR settings |
| Color saturation | ✓ | ✓ | ✓ | ✓ | 6 colors, logos at ΔE_ITP 24/12/6/3 from the P3 (HDR) or BT.709 (SDR) primary |
| Smooth gradients | ✓ | ✓ | ✓ | ✓ | Banding |
| Sharpness & picture fit | ✓ | ✓ | ✓ | ✓ | 1-px edge frame (overscan), halos, scaling |
| Motion | ✓ | ✓ | ✓ | ✓ | 23.976p pan; the app switches the display to 24 Hz |
| Shadow detail finder | ✓ | ✓ | ✓ | | Near-black levels |
| Auto-dimming | ✓ | ✓ | | | 1,000-nit window from 1% to 100% |
| HDR label effect | ✓ | | | | The same picture tagged 1,000 / 4,000 / 10,000 nits |

## How the patterns are made

The patterns are video, so the TV uses the same HDR10 or Dolby Vision picture mode it uses for movies.
`tools/generate_patterns.py` is the single source of truth: it writes the clips and the `manifest.json` the app
reads its levels from.

- **Encoding:** levels use SMPTE ST 2084 (PQ) or BT.1886 and are written as exact 10-bit limited-range Y'CbCr codes,
  encoded losslessly as HEVC Main 10.
- **Metadata:** HDR10 MaxCLL/MaxFALL and Dolby Vision L1 are measured from the frames. Dolby Vision clips are
  Profile 8.1: the same base layer plus metadata.
- **Verification:** every clip is decoded and compared with the intended code values, both by the generator and
  by CI (`--verify-only`).

## Build

JDK 17+ and the Android SDK are required.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The emulator can't decode 10-bit HEVC, so test on real hardware. For release signing, add a git-ignored
`keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

### Patterns

Everything needed to generate the patterns (x265, dovi_tool, MP4Box and Python) is in a Docker image, so nothing has
to be installed locally:

```sh
docker build -t picture-tuner-tools -f tools/Dockerfile .
alias pt-tools='docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/work" picture-tuner-tools'

pt-tools python3 tools/generate_patterns.py                  # regenerate all (4K + 1080p) and verify
pt-tools python3 tools/generate_patterns.py --only black     # selected families
pt-tools python3 tools/generate_patterns.py --verify-only    # check the committed clips
pt-tools python3 branding/export_android.py                  # regenerate icons, banner, store graphics
```

A full run takes about 10–20 minutes and needs about 8 GB of memory for Docker. Regenerated clips have identical
pixels (the generator verifies every one), but their files can differ byte-for-byte from the committed ones if the
encoder version differs, so commit regenerated clips only for families you changed.

Without Docker: `brew install ffmpeg dovi_tool gpac cairo` and `pip install -r tools/requirements.txt CairoSVG`, then
run the same commands without `pt-tools`.

### Branding

Logo sources live in `branding/luma-dial-source/`. `branding/export_android.py` (also in the Docker image, see above)
generates the launcher icons, the TV banner and the store graphics (`fastlane/metadata/android/en-US/images/`), and
validates them.

## Contributing

Corrections to TV menu names and test reports from real TVs are especially welcome. See
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

[MIT](LICENSE). The logo lettering uses Nunito Sans (SIL OFL 1.1, included in `branding/luma-dial-source/fonts/`).
The app uses [AndroidX Media3](https://github.com/androidx/media) (Apache 2.0).

Dolby Vision, HDR10+, Filmmaker Mode, HGIG, LG, Samsung, Sony and NVIDIA are trademarks of their respective owners.
Picture Tuner is not affiliated with or endorsed by any of them.
