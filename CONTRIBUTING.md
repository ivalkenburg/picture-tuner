# Contributing to Picture Tuner

Thanks for helping! The project's first rule is **correctness**: a test pattern or tip that's slightly wrong
gives people wrong advice about their TV.

## Most useful contributions

1. **Menu-name corrections.** If a tip names a setting your TV calls something else, open an issue with: TV brand,
   model and year, the exact menu name (a photo of the menu is ideal), and which screen showed the wrong name.
   Names live in `Text.name()` in `app/src/main/java/com/igorv/picturetuner/Catalog.kt`. Please only add names you've
   checked on a real TV or in the official manual.
2. **Test reports.** Streaming box or TV model, which format (HDR10 / Dolby Vision / SDR), and what happened:
   whether the TV switched into the right mode, whether the clips played, and whether the 24 Hz switch worked.
3. **New brands.** Add a `Brand` entry with verified names for every `Setting`.

## Changing patterns

- Change `tools/generate_patterns.py`, never the clips by hand, then regenerate the affected families:
  `python3 tools/generate_patterns.py --only <family>`. The script refuses to finish unless every clip decodes
  bit-exact and has the Main 10 profile.
- Commit the regenerated `.mp4` files together with `manifest.json`.
- Run `python3 tools/generate_patterns.py --verify-only` before opening a pull request. The Release workflow runs it too.
- Explain the reasoning for any new level or threshold in the pull request (which standard, which value and why).

## Code

- Kotlin, plain Android views, AndroidX Media3. Keep it simple and remote-friendly: every screen must be fully
  usable with a D-pad.
- Check that `./gradlew assembleDebug` passes.
- User-facing text should be plain language. Avoid jargon, or explain it the first time it appears.
