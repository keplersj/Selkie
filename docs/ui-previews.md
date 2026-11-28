# UI previews and screenshots

Every screen has Compose `@Preview`s in `app/src/main/kotlin/io/github/keplersj/selkie/ui/Previews.kt`, built
from fixed fixtures (`Fixtures`). They show up in Android Studio's preview pane, and
[Roborazzi](https://github.com/takahirom/roborazzi) turns each one into a Robolectric screenshot test, so they
render to PNG on the JVM with no emulator and no native binaries.

```
./gradlew -Pselkie.allowMissingNative=true recordRoborazziDebug    # write PNGs to app/screenshots/
./gradlew -Pselkie.allowMissingNative=true compareRoborazziDebug   # diff against them (app/build/outputs/roborazzi/)
./gradlew -Pselkie.allowMissingNative=true verifyRoborazziDebug    # fail if anything changed
```

## Adding a preview

- Annotate a top-level, non-private `@Composable` in the `ui` package with `@PhonePreviews` (light + dark) and,
  for screens where layout matters at tablet size, `@TabletPreview` (Galaxy Tab S11, landscape).
- Wrap it in `PreviewFrame { }`: `SelkieTheme(dynamicColor = false)` plus a `Surface`, so colors don't depend
  on the wallpaper and dark mode has a background.
- Keep it deterministic: pass data in, no I/O. Screens that read the device (`StatFs`, `DroidcClient`) take
  that value as a parameter with the real call as its default (`free = freeBytes()`, `AboutContent`).
- `TerminalView` needs a live session, so the terminal preview covers its chrome (`SessionBar`, `ExtraKeys`).

## Re-recording in CI

No local Android SDK is needed to update the goldens. The `record-screenshots` job in
`.github/workflows/screenshots.yml` clears `app/screenshots/`, runs `recordRoborazziDebug` and commits any changed
PNGs back to the branch as `github-actions[bot]`. It also uploads them as the `recorded-screenshots` artifact.

- Put `[record screenshots]` in a pushed commit's message to run it on that branch.
- You can also start it from Actions > Record UI screenshots > Run workflow, with the branch picked.
- It never runs on `main`. Its commit starts no CI run, so the branch's next push verifies the new PNGs.
- Look over the PNGs in the bot's commit before merging: the job records whatever the previews render.

## Notes

- Robolectric renders at SDK 35 (`robolectricConfig` in `app/build.gradle.kts`) and needs JDK 21.
- ComposablePreviewScanner is pinned to 0.9.x: 0.10 changed `ComposablePreview.invoke`, which Roborazzi 1.79
  calls.
- The generated tests also run in `testReleaseUnitTest`; they record nothing unless a `*Roborazzi*` task runs.
