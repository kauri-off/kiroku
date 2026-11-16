# Kiroku roadmap

Version 1.0 is milestones M1 to M7; "After 1.0" says what comes next and what is not built. What the result must
be is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); this file says in which order it is built and how each step
is accepted.

Only the milestone in progress is broken into tasks. A later milestone has a goal, an acceptance (what the owner
can do when it is finished) and the things its planner must not forget; it becomes tasks when it starts. A layout
task of a later milestone may run ahead of it, on fixture states. At the end of each milestone work stops and the
owner looks at the result.

| Milestone | Result |
|---|---|
| M1 | An installable debug app in the real design, on demo data |
| M2 | Sign-in, the real list, the real title page |
| M3 | Episodes aired and episodes to watch |
| M4 | Reliable episode notifications |
| M5 | Search and browse |
| M6 | Profiles and statistics |
| M7 | Settings, polish and release 1.0 |

M2 to M4 are the daily loop that replaces dailyAL for the owner; search and profiles come after it.

How to read a task:

- `[ ]` becomes `[x]` in the pull request that completes the task, which also cuts the entry down to its name.
- **[A]** architectural task: done by the stronger model, because later tasks build on its choices.
- **[L]** layout task: finished only when it passes [docs/LAYOUT_RUBRIC.md](docs/LAYOUT_RUBRIC.md) with both
  reviewers. The **cases** it names go into the screen's registry file, which holds them from then on.
- **[O]** a step done by the orchestrator or the owner, not by a coding agent.
- **Needs** lists what must be merged first. Tasks without a common dependency can run in parallel.
- **Check** is what a reviewer or the owner runs or looks at, and what it shows. How the task is built, and what
  its parts are called, is the author's decision.
- A section number without a document name refers to `docs/ARCHITECTURE.md`.

---

## M1. An installable app on demo data

Goal: the owner installs a debug APK and sees the List and the title page in the final design, in Russian and
English, light and dark, on demo data. No MAL client id is needed.

- [x] **M1-01 [A] Project skeleton.**
- [x] **M1-02 [A] Queue wrappers.**
- [x] **M1-03 [O] Repository published.**
- [x] **M1-04 CI workflow.**
- [x] **M1-05 [A] Domain model and fixtures.**
- [x] **M1-06 [A] Screenshot harness.**
- [x] **M1-07 [L][A] Theme and components.**
- [x] **M1-08 [L] App shell.**
- [x] **M1-09 [L] List screen.**
- [x] **M1-10 [L] Title page.**
- [x] **M1-11 [L] Title sub-screens.**
- [x] **M1-12 [L] Edit sheet.**
- [x] **M1-13 [A] Wiring and demo mode.**
- [x] **M1-14 View models, routes and the first APK.**

- [x] **M1-15 [O] Owner's look.**

## M2. Sign-in, the real list, the real title page

Goal: the owner signs in and uses their real list and real title pages (ARCHITECTURE sections 3, 7 and 9.2 to 9.5).

Acceptance: over three days of use, every list change made in the app is on the website and the reverse; no
sign-out happened that the owner did not cause; three title pages match myanimelist.net for related titles and
statistics; a `myanimelist.net/anime/{id}` link, once allowed in Android's settings, opens the title page with the
List beneath it; the owner's findings on the Russian strings so far are fixed.

- [x] **M2-01 [O] Client id on the machine.**

- [x] **M2-02 [O] Names for recording.**
- [x] **M2-03 [O] Frame for the first wave.**

- [x] **M2-04 [A] Network foundation.**

- [x] **M2-05 [A] Tokens and session.**

- [x] **M2-06 [A] List engine for a database.**

- [x] **M2-07 [L] Sign-in screen.**

- [x] **M2-08 [A] Sign-in flow.**

- [x] **M2-09 [A] Live graph and the choice of data source.**

- [x] **M2-10 [A] Recorder for answers that need only the client id.**

- [x] **M2-11 Sign-in in the app.**

- [x] **M2-12 [A] Recorder for answers that need the sign-in.**

- [ ] **M2-13 [O] The owner signs in and records.** With the debug build: he signs in; signs in once more with
  the app killed while the browser is open; runs the recorder on a title that is not on his list. On the website
  he completes a title with an empty start date, steps a Completed title below its total and completes a title
  that has a finish date, and says what MAL did (W7). The orchestrator pulls the files into the owner's folder
  outside every checkout (section 11.1), checks them for a token and the client id, and corrects section 3.1 and
  items 1 and 2 of section 13.
  **Needs:** M2-11, M2-12.
  **Check:** the recorded answers of M2-12 are in that folder and the tests that read them pass; `git grep` for the
  owner's and the two users' names finds nothing; his list on myanimelist.net is as it was; a difference from W7 is
  filed as a task.

- [x] **M2-14 Title client and mappers.**

- [x] **M2-15 List client: reading.**

- [x] **M2-16 [A] Database: the titles and the list's store.**

- [x] **M2-17 Anime repository.**

- [x] **M2-18 Translated MAL values and the genre table.**

- [x] **M2-19 List client: writing.**

- [x] **M2-20 Sandbox.**

- [x] **M2-21 The real list.**

- [ ] **M2-34 A write carries only what the user changed.** The engine hands the client the values MAL last
  confirmed, and a write sends only the values that differ from them, so neither a value MAL gave that the app could
  not read nor one changed on the website since the last download is written over. The owner decides first: a
  website edit of another value then stands, which changes how W1 and W4 read.
  **Needs:** M2-21 and the owner's decision.
  **Check:** a write after an unreadable score leaves the score out, replacing the test that it sends `score=0`; a
  step and an edit-sheet save each send their changed values alone; `ListEngineContract` passes.

- [x] **M2-22 Screens refresh by themselves.**

- [x] **M2-23 List state for a real store.**

- [x] **M2-24 [L] Title page for a real store.**

- [x] **M2-25 Posters from the network.**

- [x] **M2-26 The real title page and its link.**

- [x] **M2-27 [L] Watch order on the title page.**

- [x] **M2-28 Back gesture preview.**

- [x] **M2-29 Messages that are the only way back.**

- [ ] **M2-30 List fling cost.** The one long frame of a fling of a long tab back to the top is explained with
  measurements on the preview build, and what is the app's own is removed. The hand-off says, with numbers,
  whether a baseline profile is worth its module now or with the release.
  **Needs:** nothing.
  **Check:** a test that counts the work of the fling's frames, in the style of `ListFrameWorkTest`; frame times
  before and after from `tools/emu`, in the hand-off.

- [x] **M2-31 Changed screenshots in CI.**

- [ ] **M2-32 Compose and Material update.** When a Compose BOM brings material3 1.5.0 stable, the pin goes.
  Then: whether `MaterialExpressiveTheme` now supplies the Expressive type scale itself, which the theme's own
  `ExpressiveTypography` would hide while `TypographyTest` stays green; and whether Compose's
  `LayoutNode … not found in RectList` crash is fixed, before the List composes pages during a gesture again.
  **Needs:** the release. Without one before M2-33, this entry moves to M3.
  **Check:** all screenshots rendered before and after, and the differences named in the pull request.

- [ ] **M2-33 [O] Milestone check.** The orchestrator runs the app once on the emulator against the sandbox and
  measures a list of 3000 entries on the phone. The owner uses the preview build for three days and reviews the
  Russian strings.
  **Needs:** M2-18, M2-20, M2-21, M2-23, M2-25, M2-26, M2-28, M2-29, M2-31.
  **Check:** the acceptance of M2; his findings are filed as tasks and the ones on Russian strings are fixed.

## M3. Episodes aired and episodes to watch

Goal: the List and the title page show aired counts, "to watch" and next episodes, and Watching is ordered "Up
next" (ARCHITECTURE section 4; the row and block cases of sections 9.3 and 9.4).

Acceptance: at the start, the aired counts of three airing titles, one of them on a break, agree with a source
other than AniList (the broadcaster's or a streaming service's schedule). Over seven days the owner finds no wrong
"aired" or "to watch" value on a title with an exact count.

Not to forget:

- AniList needs no key: the agent records its responses itself, among them a title without a schedule, a title on a
  break and a MAL id AniList does not know.
- During the seven days the app counts, over the owner's list, how many titles get an exact count, how many are
  rejected for their numbering (A4) and how many are only estimated (A5, A6). The counts settle item 3 of
  section 13.
- The rule that rows never move (section 9.3) is tested with airing data changing under a visible tab.
- The calculator's result also carries "to watch" and the premiere, so no screen combines them on its own (A1).
- Airing is observed with one clock tick from `:core`, shared by demo mode and `:data`, so every screen moves to the
  next episode at the same instant (A2); the title page fetches airing for a title outside the tracked set (A12).
- A next episode beyond MAL's total (AniList counting a recap MAL does not) is clamped: the aired count stops at
  the total and there is no next episode beyond it, shown by a calculator test. An AniList episode numbered 0 or
  below is dropped.

## M4. Reliable episode notifications

Goal: the notifications of ARCHITECTURE section 5 and the notification settings of section 9.8.

Acceptance: over seven days with notifications on, every entry of "Recently sent" matches an episode that had aired
by AniList's schedule for a title the owner was watching; no episode of a watched title with an exact count is
missing from it; there are no duplicates.

Not to forget:

- What is notifiable is written and tested in `:core` first, with every dailyAL failure case.
- The emulator run of section 5.2 forces Doze and restarts the device. It cannot use demo mode, whose data does not
  survive a restart, and a refresh must not overwrite the episode it plants. It measures the delay of the
  notification and whether the re-check reached the network, which settles item 4 of section 13.
- The "Mark watched" action is tested on an Android level before 12 as well as on a current one (N9).
- An edit sheet opened before a "Mark watched" and saved after it sends the older count, since the last value shown
  wins (W1); decide whether the action's episode survives a save that did not change the count.
- A held Retry or Undo must not put old values over ones written in the background (the "Mark watched" action, a
  sync).
- By hand on Android 13 or later: deny the permission, see "Blocked in system settings", grant it in the system
  settings, return, see the state updated, receive the test notification.

## M5. Search and browse

Goal: search, the four browse lists and the Recommendations sub-screen (ARCHITECTURE sections 9.6 and 9.9).

Acceptance: the owner finds a title by name and opens it; a title on their list shows its status in the results;
each browse list opens and loads further pages; a query of digits offers "Open title #<id>".

Not to forget:

- Searches of one, two and three characters are recorded, to learn the shortest query MAL accepts (item 2 of
  section 13).
- Typing does not send a request per keystroke.
- Results that arrive page by page are held as a `StateFlow` of the pages loaded.

- [x] **M5-01 [L] Search screen.**

## M6. Profiles and statistics

Goal: the owner's profile, another user's profile and list, and the statistics sub-screens (ARCHITECTURE sections
9.7 and 9.9).

Acceptance: the owner's completed count and mean score equal those on their MAL profile page ("Days watched" may
differ, because MAL counts rewatches); another user's profile opens by name and by a `myanimelist.net/profile/…`
link, also for a list of several thousand entries; a private list and an unknown name each say what they are. The
owner's findings on the Russian strings of M3 to M6 are fixed.

Not to forget:

- One statistics calculator in `:core` serves the owner and other users, tested against a list computed by hand.
- The answers for a public list, a private list and an unknown name are recorded, and settle how a private list is
  told apart from rate limiting (item 2 of section 13). Without a private user name from the owner, that stays
  open and the task says so.

- [x] **M6-01 [L] Profile screen.**

## M7. Settings, polish and release 1.0

Goal: the Settings and About screens (ARCHITECTURE section 9.8), every screen on wide windows and with a screen
reader, and a signed release.

Acceptance: a tag `v0.9.0` produces a signed APK on a GitHub Release that installs next to the debug build and signs
in. After two weeks of daily use of `v0.9.x`, "Recently sent" meets the acceptance of M4 and no list change was
lost; the Russian strings of M7 are reviewed; then the orchestrator tags `v1.0.0`.

Not to forget:

- A wide-window pass over every screen, as one more variant, `en-wide`, of each content case: 840 × 600 dp and
  600 × 960 dp, the rail, the content width limit, landscape.
- A screen-reader pass with TalkBack over List, Title and Profile: every interactive element has spoken text that
  names its action or value, and the reading order is the visual order.
- A walk through every screen with the network off, on a slow network and with MAL refusing requests, compared
  with ARCHITECTURE sections 2.3 and 7.2.
- A privacy statement (what is stored, which servers are contacted and what is sent to each) in which every
  sentence is backed by code; `CONTRIBUTING.md` with the checks outside the queue; a README from which a clean
  checkout builds without guessing.
- The system's per-app language screen lists English and Russian.
- The release key exists and its four secrets (`KIROKU_ANDROID_KEYSTORE_B64`, `KIROKU_ANDROID_STORE_PASSWORD`,
  `KIROKU_ANDROID_KEY_ALIAS`, `KIROKU_ANDROID_KEY_PASSWORD`) are in the `release` environment; the release
  workflow is built as ARCHITECTURE section 12 describes. A release never falls back to another key.

---

## After 1.0

This section accounts for every other dailyAL feature: it is planned, or it is not built, with the reason. A
feature the owner wants back moves from "Not built" to a planned milestone. The milestones are built in the order
P1 to P5; each starts with a budget change (ARCHITECTURE section 9.1). The sources other than MAL are unchecked
(ARCHITECTURE section 13, item 5).

| Milestone | Goal | Features | Sources |
|---|---|---|---|
| P1 | Manga and a fuller list | Manga lists, title pages, search, rankings and statistics, behind an Anime / Manga switch; related manga on anime pages; rewatching, tags, comments and priority in the edit sheet; more list sorts and a filter across statuses; an offline write queue with a visible pending state; the cover opened full-screen, with the title's other pictures to swipe through; opening and ending themes; deep links to manga pages | MAL only |
| P2 | A deeper title page | Votes per score; characters, voice actors and their pages; where to watch; trailers; the whole franchise in watch order, beyond the chain of sequels; search filters, also from a title's genres; other users' avatar, "about" and favourites; more statistics charts | Jikan, AniList, MAL |
| P3 | Schedule and discovery | A weekly schedule of the user's airing titles as the fourth destination; a season browser; suggestions; more rankings; recently viewed titles; a per-title notification mute | Stored airing data, MAL |
| P4 | Community, read-only | Forum boards, topics and posts as the fifth destination; a title's discussions; users' reviews of a title | MAL, Jikan |
| P5 | Reach | F-Droid; community translations, each reviewed by a speaker; Russian titles as a fourth title language; a pure-black theme; a choice of start tab; reference screenshots compared in CI; an "Up next" widget | Shikimori for Russian titles |

### Not built

| dailyAL feature | Why not |
|---|---|
| Customisable home page with ten carousels | The main source of dailyAL's noise. Its useful parts are the browse rows in Search and the "Up next" order of the List. |
| News, featured articles, interest stacks, friends, other users' history | No API; available only by scraping myanimelist.net. "Open on MyAnimeList" covers them. |
| Clubs | No official API; rarely used on a phone. |
| User search | No API. Profiles open by exact name or link. |
| "User updates" on a title | Low value; a list of strangers' progress. |
| AI review summaries; profile background upload; server-backed autocomplete | Needed dailyAL's own server. |
| Automatic synopsis translation | Relied on an unofficial Google Translate scraper. The system's translate-selection works on the synopsis text. |
| The related-titles graph drawing | Dense and hard to read on a phone; replaced by the franchise in watch order (P2). |
| Custom list-row layout editor; grid, compact and "spacious" list modes | Each mode multiplies the screens to design and review; the editor was buggy (issue #14). One good row. |
| Local bookmarks | Looked like MAL favourites but were not (issue #102). "Planned" is the bookmark. |
| A configurable "Add to list" action (issue #36) | A setting for one tap. "Add to list" adds as Planned and its snackbar's "Change" reaches every status. |
| A time-zone preference for air times | Air times are shown in the device's time zone, which is the one the user lives in. |
| Counts on the list tabs | Numbers on every tab are noise on the start screen; the Profile shows the counts per status. |
| A title-language toggle on each page | One "Title language" setting applies everywhere; Details lists the alternative titles. |
| Local private notes on an entry | Not synchronised anywhere; lost on reinstall. MAL comments (P1) cover it. |
| Search query syntax (`#genre`, `@user`) | A hidden feature. Filters arrive in P2. |
| Explore → Random | A novelty. |
| Dub information | The data set's licence is unknown. |
| Add an airing time to the system calendar | Notifications and the Schedule (P3) cover the need. |
| Seasonal background photos, accent-colour picker, font choice | Against the design system; dynamic colour is the personalisation. |
| Backup and restore of settings; cache-frequency setting; remote configuration | The app has few settings and its data lives on MAL; the rest is internal. |
| Desktop, iOS and web builds | Android only, by decision. |
| Donation link | The owner's decision; none by default. |
