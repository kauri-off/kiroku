# Kiroku roadmap

Version 1.0 is milestones M1 to M7; "After 1.0" says what comes next and what is not built. What the result must
be is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); this file says in which order it is built and how each step
is accepted.

Only the milestone in progress is broken into tasks. Later milestones define a goal, acceptance criteria and constraints. Break each milestone into tasks when it starts.
Screens for later milestones may be built early using fixture states. Each milestone ends with an acceptance review.

| Milestone | Result |
|---|---|
| M1 | An installable debug app in the real design, on demo data |
| M2 | Sign-in, the real list, the real title page |
| M3 | Episodes aired and episodes to watch |
| M4 | Reliable episode notifications |
| M5 | Search and browse |
| M6 | Profiles and statistics |
| M7 | Settings, polish and release 1.0 |

M2 to M4 are the daily loop that replaces dailyAL; search and profiles come after it.

How to read a task:

- `[ ]` becomes `[x]` in the pull request that completes the task, which also cuts the entry down to its name.
- **[A]** architectural task: done by the stronger model, because later tasks build on its choices.
- **[O]** an orchestration or manual acceptance step.
- **Needs** lists what must be merged first. Tasks without a common dependency can run in parallel.
- **Check** is the evidence used to verify the task. How the task is built, and what
  its parts are called, is the author's decision.
- A section number without a document name refers to `docs/ARCHITECTURE.md`.

---

## M1. An installable app on demo data

Goal: install a debug APK and verify the List and the title page in the final design, in Russian and
English, light and dark, on demo data. No MAL client id is needed.

- [x] **M1-01 [A] Project skeleton.**
- [x] **M1-02 [A] Queue wrappers.**
- [x] **M1-03 [O] Repository published.**
- [x] **M1-04 CI workflow.**
- [x] **M1-05 [A] Domain model and fixtures.**
- [x] **M1-06 [A] Screenshot harness.**
- [x] **M1-07 [A] Theme and components.**
- [x] **M1-08 App shell.**
- [x] **M1-09 List screen.**
- [x] **M1-10 Title page.**
- [x] **M1-11 Title sub-screens.**
- [x] **M1-12 Edit sheet.**
- [x] **M1-13 [A] Wiring and demo mode.**
- [x] **M1-14 View models, routes and the first APK.**

- [x] **M1-15 [O] Visual acceptance.**

## M2. Sign-in, the real list, the real title page

Goal: sign in and use a real list and live title pages (ARCHITECTURE sections 3 and 7).

Acceptance: every list change syncs between the app and the website in both directions. No unexpected sign-out occurs.
Three title pages match myanimelist.net for related titles and statistics. A `myanimelist.net/anime/{id}` link,
once allowed in Android's settings, opens the title page with the List beneath it. Issues found in the Russian
strings are fixed.

- [x] **M2-01 [O] Client id on the machine.**

- [x] **M2-02 [O] Names for recording.**
- [x] **M2-03 [O] Frame for the first wave.**

- [x] **M2-04 [A] Network foundation.**

- [x] **M2-05 [A] Tokens and session.**

- [x] **M2-06 [A] List engine for a database.**

- [x] **M2-07 Sign-in screen.**

- [x] **M2-08 [A] Sign-in flow.**

- [x] **M2-09 [A] Live graph and the choice of data source.**

- [x] **M2-10 [A] Recorder for answers that need only the client id.**

- [x] **M2-11 Sign-in in the app.**

- [x] **M2-12 [A] Recorder for answers that need the sign-in.**

- [x] **M2-13 [O] Sign in and record API responses.**

- [x] **M2-14 Title client and mappers.**

- [x] **M2-15 List client: reading.**

- [x] **M2-16 [A] Database: the titles and the list's store.**

- [x] **M2-17 Anime repository.**

- [x] **M2-18 Translated MAL values and the genre table.**

- [x] **M2-19 List client: writing.**

- [x] **M2-20 Sandbox.**

- [x] **M2-21 The real list.**

- [x] **M2-34 A write carries only what the user changed.**

- [x] **M2-22 Screens refresh by themselves.**

- [x] **M2-23 List state for a real store.**

- [x] **M2-24 Title page for a real store.**

- [x] **M2-25 Posters from the network.**

- [x] **M2-26 The real title page and its link.**

- [x] **M2-27 Watch order on the title page.**

- [x] **M2-35 MAL scores of a title.**

- [x] **M2-28 Back gesture preview.**

- [x] **M2-29 Messages that are the only way back.**

- [ ] **M2-30 List fling cost.** The one long frame of a fling of a long tab back to the top is explained with
  measurements on the preview build, and what is the app's own is removed. The hand-off says, with numbers,
  whether a baseline profile is worth its module now or with the release.
  **Needs:** nothing.
  **Check:** a test that counts the work of the fling's frames, in the style of `ListFrameWorkTest`; frame times
  before and after on an emulator, in the hand-off.

- [x] **M2-31 Changed screenshots in CI.**

- [ ] **M2-32 Compose and Material update.** When a Compose BOM brings material3 1.5.0 stable, the pin goes.
  Then: whether `MaterialExpressiveTheme` now supplies the Expressive type scale itself, which the theme's own
  `ExpressiveTypography` would hide while `TypographyTest` stays green; and whether Compose's
  `LayoutNode … not found in RectList` crash is fixed, before the List composes pages during a gesture again.
  **Needs:** the release. Without one before M2-33, this entry moves to M3.
  **Check:** all screenshots rendered before and after, and the differences named in the pull request.

- [ ] **M2-33 [O] Milestone check.** The orchestrator runs the app once on the emulator against the sandbox and
  measures a list of 3000 entries on the phone. Check the preview build and review the Russian strings.
  **Needs:** M2-18, M2-20, M2-21, M2-23, M2-25, M2-26, M2-28, M2-29, M2-31.
  **Check:** the acceptance of M2; file findings as tasks and fix the Russian string issues.

## M3. Episodes aired and episodes to watch

Goal: the List and the title page show aired counts, "to watch" and next episodes, and Watching is ordered "Up
next" (ARCHITECTURE section 4).

Acceptance: the aired counts of three airing titles, including one on a break, match broadcaster or streaming
schedules. Verify that "aired" and "to watch" values are correct for titles with exact counts.

- [x] **M3-01 Recorded answers of AniList.**

- [x] **M3-02 [A] Airing calculator.**

- [x] **M3-03 [A] Airing refresh.**

- [x] **M3-04 [A] One tick, and demo mode on it.**

- [x] **M3-05 Database: the airing store.**

- [x] **M3-06 AniList client and mappers.**

- [x] **M3-07 Sandbox: AniList on the device.**

- [x] **M3-08 Live airing.**

- [x] **M3-09 The List and the title page on live airing.**

- [x] **M3-10 Airing counts in the debug menu.**

- [x] **M3-11 AniList in the scenario panel.**

- [x] **M3-12 [O] Emulator run.**

- [ ] **M3-13 [O] Airing check.** In the preview build, compare aired counts for three titles on the test list,
  including one on a break, against broadcaster or streaming schedules. Use the M3-10 counts from the phone to
  resolve item 3 of section 13.
  **Needs:** M3-12.
  **Check:** the acceptance of M3; item 3 of section 13 holds the counts; a wrong value is filed as a task with
  its reason from M3-10.

## M4. Reliable episode notifications

Goal: the notifications of ARCHITECTURE section 5 and their settings.

Acceptance: with notifications on, every entry of "Recently sent" matches an episode that had aired by
AniList's schedule for a title in Watching. No episode of a watched title with an exact count is missing,
and there are no duplicates.

- [x] **M4-01 [A] What is notifiable.**

- [x] **M4-02 [A] Notification engine.**

- [x] **M4-03 Background writes and the edits a screen holds.**

- [x] **M4-04 [A] One way in to MAL for background work.**

- [x] **M4-05 Notification settings screen.**

- [x] **M4-06 [O] An emulator of Android 8 to 11.**

- [x] **M4-07 Database: the record and the settings.**

- [x] **M4-08 The notification on the device.**

- [x] **M4-09 [A] The alarm and the moments the app runs.**

- [x] **M4-10 Live notifications.**

- [x] **M4-11 Periodic background work.**

- [x] **M4-12 "Mark watched" from the notification.**

- [x] **M4-13 Notification settings in the app.**

- [x] **M4-14 The permission prompt.**

- [x] **M4-15 Notifications in the debug menu.**

- [x] **M4-16 [O] Emulator run.**

- [ ] **M4-17 [O] Notification check.** Use the preview build with notifications enabled during normal phone
  use. Compare the sent notifications with AniList's schedule for titles in Watching.
  **Needs:** M4-16, M3-13.
  **Check:** the acceptance of M4; a missing, a duplicate or a wrong notification is filed as a task with its lines
  of M4-15's log and the states the notification settings showed.

## M5. Search and browse

Goal: anime search, public browse lists, personalised suggestions and title recommendations.

Acceptance: find a title by name and open it; a title on the signed-in list shows its status in the results;
each browse list opens and loads further pages; a query of digits offers "Open title #<id>".

Not to forget:

- Typing does not send a request per keystroke.
- Results that arrive page by page are held as a `StateFlow` of the pages loaded.

- [x] **M5-01 Search screen.**

- [x] **M5-02 Live title search.**

- [x] **M5-03 Live browse lists.**

- [x] **M5-04 Recommendations.**

- [x] **M5-05 Personalised anime suggestions.**

## M6. Profiles and statistics

Goal: the signed-in profile, another user's profile and list, and the statistics sub-screens.

Acceptance: completed count, mean score, days and episodes watched match the signed-in MAL profile;
another user's profile opens by name and by a `myanimelist.net/profile/…` link, also for a list of several thousand
entries; a private list and an unknown name each say what they are. Issues found in the Russian strings of M3 to M6 are fixed.

- [x] **M6-01 Profile screen.**

- [x] **M6-02 Statistics calculator.**

- [x] **M6-03 Recorded answers of another user's list.**

- [x] **M6-04 [A] Other users' lists.**

- [x] **M6-05 The signed-in user's profile.**

- [x] **M6-06 Profile links.**

- [x] **M6-07 Sandbox: other users.**

- [x] **M6-08 Another user's list from MAL.**

- [x] **M6-09 The Profile on real state.**

- [x] **M6-10 Live profiles.**

- [ ] **M6-11 [O] Profile design review.** Review the prototypes on a phone and choose the Profile layout,
  including the placement of statistics and the other user's list.
  **Needs:** nothing.
  **Check:** one composition is chosen; M6-12, M6-13 and M6-14 start from its prototype.

- [x] **M6-12 The Profile as chosen.**

- [x] **M6-13 Statistics views.**

- [x] **M6-14 Another user's list in the app.**

- [x] **M6-15 [O] Emulator run.**

- [ ] **M6-16 [O] Live profile acceptance.** In Preview, compare the signed-in Profile's completed count and
  mean score with the MAL profile. Open a profile with several thousand public list entries by name and by a link
  from another app after enabling app links in Android. Check private and unknown profiles and review the Russian
  strings of M3 to M6.
  **Needs:** M6-15.
  **Check:** the acceptance of M6; file discrepancies with the displayed text and fix issues found in the Russian strings.

## M7. Settings, polish and release 1.0

Goal: the Settings and About screens, every screen on wide windows and with a screen reader, and a signed
release.

Acceptance: a tag `v0.9.0` produces a signed APK on a GitHub Release that installs next to the debug build and signs
in. On `v0.9.x`, "Recently sent" meets the acceptance of M4 and no list change is lost. The Russian strings
of M7 are reviewed. The orchestrator then tags `v1.0.0`.

Not to forget:

- A wide-window pass over every screen, as one more variant, `en-wide`, of each content case: 840 × 600 dp and
  600 × 960 dp, the rail, the content width limit, landscape.
- A screen-reader pass with TalkBack over List, Title and Profile: every interactive element has spoken text that
  names its action or value, and the reading order is the visual order.
- A walk through every screen with the network off, on a slow network and with MAL refusing requests, compared
  with ARCHITECTURE sections 2.3 and 7.2.
- [x] Contributor build guide, fork setup and privacy statement.
- [x] Android app language.
- [x] Version-tag release workflow.
- [ ] Configure the release key in the restricted `release` environment.
- [x] Live title language and adult-content settings.
- [x] About screen.
- [x] Clear image caches on sign-out (S9).
- [x] Restore the idle top bar after a canceled pull-to-refresh.
- [x] Retry offline own Profile reads on reconnect.
- [x] Filter the own and public anime lists by title across statuses.
- [x] Manga title pages from MyAnimeList links.
- [x] Manga search and ranking.
- [x] Read the personal manga list.
- [x] Switch between Anime and Manga in My list.
- [x] Manga status tabs in My list.
- [x] Keep automatic Manga list refreshes in the background.
- [x] Filter and sort the personal manga list.
- [x] Edit personal manga list entries.
  - [x] Owner-bound edits and read-back reconciliation.
- [x] Remove entries from the personal manga list.
- [x] Add manga to Planned from title pages.
- [x] Record personal manga list changes.
- [x] Delete individual recent searches.
- [x] Open anime from MyAnimeList links pasted in Search.

---

## Completed enhancements

- [x] Open anime and manga covers full-screen.
- [x] View another user's public manga list.
- [x] Preserve remaining snackbar time across destination changes.
- [x] Swipeable gallery for anime and manga titles.
- [x] Pure-black theme.
- [x] Choice of startup tab.

## After 1.0

This section accounts for every other dailyAL feature: it is planned, or it is not built, with the reason. A
feature selected for implementation moves from "Not built" to a planned milestone. The milestones are built in the order
P1 to P5. The sources other than MAL are unchecked (ARCHITECTURE section 13, item 5).

| Milestone | Goal | Features | Sources |
|---|---|---|---|
| P1 | Manga and a fuller list | Manga statistics; related manga on anime pages; rewatching, tags, comments and priority in the edit sheet; more list sorts; an offline write queue with a visible pending state; opening and ending themes | MAL only |
| P2 | A deeper title page | Characters, voice actors and their pages; where to watch; trailers; the whole franchise in watch order, beyond the chain of sequels; search filters, also from a title's genres; other users' avatar, "about" and favourites; more statistics charts | AniList, MAL, one not chosen yet |
| P3 | Schedule and discovery | A weekly schedule of the user's airing titles; a season browser; more rankings; recently viewed titles; a per-title notification mute | Stored airing data, MAL |
| P4 | Community, read-only | Forum boards, topics and posts; a title's discussions; users' reviews of a title | MAL, one not chosen yet |
| P5 | Reach | F-Droid; community translations, each reviewed by a speaker; Russian titles as a fourth title language; reference screenshots compared in CI; an "Up next" widget | Shikimori for Russian titles |

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
| Donation link | None by default. |
