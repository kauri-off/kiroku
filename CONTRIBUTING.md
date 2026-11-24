# Contributing to Kiroku

Start with the [Demo build](README.md#building). Gradle also accepts `sdk.dir` in the checkout's untracked
`local.properties`. If you leave `ANDROID_HOME` unset, set `sdk.dir` to the absolute SDK path and use that path
in place of `$ANDROID_HOME` in the README's `sdkmanager` commands.

Before changing code, read the relevant entries in the [roadmap](ROADMAP.md) and [architecture](docs/ARCHITECTURE.md).
Follow the conventions in [CLAUDE.md](CLAUDE.md), including Conventional Commits for pull request titles.
Pull requests are squash-merged. For data fixtures, follow the [recording rules](docs/ARCHITECTURE.md#111-tests-and-recorded-responses).

## Checks

Run these commands from the checkout root.

| Check | Command |
| --- | --- |
| Formatting | `./gradlew ktlintCheck` |
| Domain tests | `./gradlew :core:test` |
| Data tests | `./gradlew :data:testDebugUnitTest` |
| App tests | `./gradlew :app:testDebugUnitTest` |
| Full local check | `./gradlew ktlintCheck lint test assembleDebug assembleRelease assemblePreview` |

To render changed screens, pass one `--tests '*<Screen>Screenshots*'` per screen to
`./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true` in a single run. Render the full set with
`./gradlew :app:recordRoborazziDebug`. Renders are written under `app/build/outputs/roborazzi/`.
For data instrumentation tests, start an Android emulator or connect a test device, then run
`./gradlew :data:connectedDebugAndroidTest`.

[CI](.github/workflows/ci.yml) skips Markdown-only changes. For code pull requests it runs formatting, lint, tests,
screenshot renders and Debug builds, including the instrumentation APK. Run connected tests locally. Full Release
and Preview assembly runs after qualifying pushes to `main`. The
[pull request title check](.github/workflows/pr-title.yml) also runs for Markdown-only changes.

## MyAnimeList sign-in in a fork

1. Set your own base `applicationId` in [app/build.gradle.kts](app/build.gradle.kts). Keep the `.debug` and
   `.preview` suffixes so the three variants can install side by side.
2. Choose a distinct lowercase `scheme://oauth` URI for each build type. Use unique schemes so Android can route
   a callback to the intended app. An application ID can contain underscores; a URI scheme cannot. In the same
   Gradle file, update all three `oauthRedirect(...)` calls and the matching entries in `registeredRedirects`.
   The calls set the app's redirect and the manifest filter, and the table checks the merged manifests.
3. Update every `android:targetPackage` in the [Debug](app/src/debug/res/xml/debug_menu_shortcuts_debug.xml) and
   [Preview](app/src/debug/res/xml/debug_menu_shortcuts_preview.xml) shortcut XML to that variant's new application
   ID. Keep the existing Kotlin namespace and `android:targetClass` values.
4. Register your own MAL client of type Android and register the exact redirect URIs for the variants you intend
   to sign in with. Put its client ID in the local Gradle property `kiroku.malClientId` (for example, in your
   user-level `~/.gradle/gradle.properties`) or set `MAL_CLIENT_ID` in the build environment. A nonblank property
   takes precedence. The client ID is embedded in the APK. Keep it out of version control.

For an installable sign-in build, use `./gradlew :app:assembleDebug` or `./gradlew :app:assemblePreview`. Both
default to Account with a client ID and Demo without one, unless you previously chose a data source on that
install. `:app:assembleRelease` produces an unsigned APK. Sign it separately before installing it and provide
a client ID at build time to enable Release sign-in. See the
[sign-in design](docs/ARCHITECTURE.md#3-sign-in-with-mal) for the upstream configuration.
