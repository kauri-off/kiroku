# Kiroku

An unofficial Android client for [MyAnimeList](https://myanimelist.net), built with Kotlin, Jetpack Compose and
Material 3.

**Kiroku is not affiliated with, endorsed by or sponsored by MyAnimeList Co., Ltd. or AniList.** "MyAnimeList" is a
trademark. The name Kiroku (記録) is Japanese for "record".

[Русская версия](README.ru.md)

## Status

The project is in early development and has no release yet. These documents say what is being built; the code and
its tests say how:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the decisions and their reasons, and what must hold
- [ROADMAP.md](ROADMAP.md): the milestones, and what comes after the first release
- [docs/GLOSSARY.md](docs/GLOSSARY.md): the English and Russian terms used in the app

## What it will be

Kiroku is a rewrite, from scratch, of the idea behind the Flutter app
[dailyAL](https://github.com/JICA98/DailyAL), which is no longer maintained. The first release does less than dailyAL
and aims to do it more cleanly:

- your anime list, with quick episode updates;
- search, and a title page with related titles and statistics;
- for airing shows: how many episodes have aired, how many there are in total, and how many of the aired ones you
  have yet to watch;
- your profile and statistics, and other users' public lists and statistics;
- a notification when a new episode of a show you watch has aired, and no notification for anything else;
- Russian and English.

The interface is built from standard Material 3 components.

See the [privacy statement](PRIVACY.md) for what Kiroku sends and stores.

The first release is a starting point. Manga, a deeper title page, a weekly schedule and read-only forums are planned
after it; the roadmap lists what is planned and what is not built.

## Building

Set `JAVA_HOME` to your JDK 17 installation and `ANDROID_HOME` to your Android SDK directory, with the SDK
command-line tools in `cmdline-tools/latest`. The Gradle wrapper downloads Gradle.

```sh
git clone https://github.com/kauri-off/kiroku.git
cd kiroku
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "platforms;android-37.2"
./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on Android 8.0 or later. A fresh Debug install built without a
MyAnimeList client ID opens in Demo mode without an account.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for checks and fork setup, including
MyAnimeList sign-in.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).
