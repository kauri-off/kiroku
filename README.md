# Kiroku

An unofficial Android client for [MyAnimeList](https://myanimelist.net), built with Kotlin, Jetpack Compose and
Material 3.

**Kiroku is not affiliated with, endorsed by or sponsored by MyAnimeList Co., Ltd. or AniList.** "MyAnimeList" is a
trademark of its owner. The name Kiroku (記録) is Japanese for "record".

[Русская версия](README.ru.md)

## Status

The project is in early development and has no release yet. These documents say what is being built; the code and
its tests say how:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the decisions and their reasons, what must hold, and what each
  screen shows
- [ROADMAP.md](ROADMAP.md): the milestones, and what comes after the first release
- [docs/LAYOUT_RUBRIC.md](docs/LAYOUT_RUBRIC.md): how screen layouts are judged
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

Every screen is designed to an explicit limit on how much it may show, using standard Material 3 components.

Data comes from the official MyAnimeList API and, for airing times, from the AniList API. To get airing times, the app
sends AniList the MyAnimeList ids of the unfinished titles on your list and of an airing title whose page you open,
and nothing else about you. The app has no server of its own, scrapes no web pages, and contains no analytics, ads
or closed-source components.

The first release is a starting point. Manga, a deeper title page, a weekly schedule and read-only forums are planned
after it; the roadmap lists what is planned and what is not built.

## Building

A debug build needs JDK 17 and the Android SDK, and runs on demo data without any account or API key. Signing in to
MyAnimeList needs your own MAL API client id, which is never committed to this repository; `docs/ARCHITECTURE.md`,
section 3.3, explains how to supply it.

## Contributing

The code is written mostly by AI coding agents under review, following [CLAUDE.md](CLAUDE.md). Issues and pull requests
from people are welcome. Commits follow the Conventional Commits format and pull requests are squash-merged.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).
