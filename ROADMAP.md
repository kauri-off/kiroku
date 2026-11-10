# Kiroku roadmap

Version 1.0 is milestones M1 to M7; "After 1.0" says what comes next and what is not built. What the result must
be is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); this file says in which order it is built and how each step
is accepted.

Only the milestone in progress is broken into tasks. A later milestone has a goal, an acceptance (what the owner
can do when it is finished) and the things its planner must not forget; it becomes tasks when it starts. At the end
of each milestone work stops and the owner looks at the result.

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

- `[ ]` becomes `[x]` in the pull request that completes the task. Each task is one agent, one git worktree and one
  squash-merged pull request.
- **[A]** architectural task: done by the stronger model, because later tasks build on its choices.
- **[L]** layout task: finished only when it passes [docs/LAYOUT_RUBRIC.md](docs/LAYOUT_RUBRIC.md) with both
  reviewers. It renders the **cases** it names, plus content, loading, empty and error where they exist, as
  ARCHITECTURE section 11.2 requires. The review of an [L] task other than M1-07 needs the calibration set of
  M1-07a.
- **[O]** a step done by the orchestrator or the owner, not by a coding agent.
- **Needs** lists what must be merged first. Tasks without a common dependency can run in parallel.
- **Check** is what a reviewer or the owner runs or looks at, and what it shows. How the task is built, and what
  its parts are called, is the author's decision.
- A section number without a document name refers to `docs/ARCHITECTURE.md`.

Every task also passes `tools/gw --kind check ktlintCheck lint test assembleDebug`. Agents never push; a check that
needs GitHub is observed by the orchestrator after it pushes the branch.

---

## M1. An installable app on demo data

Goal: the owner installs a debug APK and sees the List and the title page in the final design, in Russian and
English, light and dark, on demo data. No MAL client id is needed.

- [x] **M1-01 [A] Project skeleton.** A Gradle project with the modules `:core`, `:data` and `:app` that builds an
  empty Compose app labelled "Kiroku", with the application ids of ARCHITECTURE section 3.1, the stack and platform
  levels of sections 6.4 and 12, one version catalog, and the format and Lint checks in place. Installs the SDK
  platform the build needs.
  **Needs:** nothing.
  **Check:** through the queue, `ktlintCheck lint test assembleDebug` exits 0 and leaves a debug APK; the build
  reports the Kotlin Gradle plugin at the version the catalog pins, not the Android Gradle Plugin's default.

- [ ] **M1-02 [A] Queue wrappers.** `tools/gw` and `tools/emu` with the behaviour `CLAUDE.md` describes, and
  `tools/emu smoke <apk>` (install, launch, device screenshots). An emulator run uses the slot the queue assigns,
  never touches the other project's emulators or their snapshots, and always shuts its emulator down.
  **Needs:** M1-01.
  **Check:** from a shell without `ANDROID_HOME`, `tools/gw :core:test` prints a job id and then the Gradle result,
  and the queue lists the job with kind `test`; a command run through `tools/emu` reports an API 36 device, and
  afterwards no Kiroku emulator is running; the pull request states the measured peak memory of a `check` job, and
  two such jobs fit the machine.

- [x] **M1-03 [O] Repository published.** The orchestrator creates the public repository `kauri-off/kiroku`, pushes
  `main`, sets squash merging as the only merge method, and adds the secret `MAL_CLIENT_ID`.
  **Needs:** M1-01.
  **Check:** `gh repo view kauri-off/kiroku` succeeds.

- [ ] **M1-04 CI workflow.** The pull-request and `main` checks of ARCHITECTURE section 12, without the
  screenshots, which M1-06 adds.
  **Needs:** M1-01. The check needs M1-03.
  **Check (after M1-03):** on the task's pull request the build check and the title check are green and the run
  offers the debug APK. Before M1-03 the reviewers read the workflow.

- [ ] **M1-05 [A] Domain model and fixtures.** The domain types, typed errors and repository interfaces of `:core`,
  and the fixture set: about 30 list entries covering every status and every row case of section 9.3 (both
  estimated cases included), a very long title, a title with unknown episode count, a title one episode short of
  its total, a film, one title for each case of the airing block (section 9.4), one title for each premiere form
  of a not-yet-aired title (a known first-episode time, a full date, a year and month, a year alone, no date), one
  fully described title with statistics and twelve related titles (every relation type, both alternative types
  among them, and at least two that are on the user's list), one user with statistics.
  **Needs:** M1-01.
  **Check:** a `:core` test asserts that each case named above is present in the fixtures.

- [ ] **M1-06 [A] Screenshot harness.** The rendering of ARCHITECTURE section 11.2, a `sample` screen with a
  scrolling list, and two deliberately bad versions of it for calibration: the cases `sample/calibration-noise`
  (repeated facts and decoration) and `sample/calibration-hierarchy` (competing primary actions). CI renders and
  keeps the screenshots.
  **Needs:** M1-04, M1-05.
  **Check:** rendering the sample screen writes, under `app/build/outputs/roborazzi/sample/`, the content case in
  all seven variants, with the full-height images and the bounds image; two runs give byte-identical files;
  `RENDERED_AT` names the current commit, and after an empty commit the same command renders again and names the
  new one; both system bars are visible in the images and the sample's last row is clear of the gesture bar; a
  test shows the accessibility check reporting a deliberately small button, without that button failing the record
  run; an ordinary `:app` test run renders nothing and reports the screenshot tests as skipped; the whole-project
  `test` still passes; the pull request states the time to render one screen.

- [ ] **M1-07 [L][A] Theme and components.** The theme (dynamic colour, the two fixed seeds, light and dark),
  edge-to-edge, the `Spacing` tokens, the ten components of section 8.4 with previews, and the test that fails on
  a string literal in user interface code (section 10), so that every later screen is written under it.
  **Cases:** `gallery/all` showing every component in every state.
  **Needs:** M1-06.
  **Check:** rubric pass on `gallery/*` under the gallery weights of the rubric; in `ru-stress` and `ru-large` the
  strip card's captions «Продолжение» and «Запланировано» are whole, neither broken inside the word nor cut; the
  string test fails on a planted literal and passes without it.

- [ ] **M1-07a [O] First calibration set.** The owner scores, with the rubric, the gallery and the three `sample`
  cases of M1-06 (`content`, `calibration-noise`, `calibration-hierarchy`), listing each deduction with its size.
  The images and the scores are committed as `docs/calibration/`. This is also the owner's first look at the
  design.
  **Needs:** M1-07.
  **Check:** `docs/calibration/` holds the four images and a score sheet in the rubric's report format; the two bad
  cases fail, each with at least one deduction marked Noticeable or Structural; the owner's remarks on the look are
  filed as tasks before M1-08 to M1-12 are reviewed.

- [ ] **M1-08 [L] App shell.** Navigation with the behaviour of section 6.3; the bar or rail with List, Search and
  Profile; placeholder screens in the shared empty state; the place for snackbars and for the session banner.
  **Cases:** `shell/list-selected`, `shell/snackbar`, `shell/session-banner`, `shell/wide` (840 × 600 dp, rail).
  **Needs:** M1-07; the review needs M1-07a.
  **Check:** rubric pass on `shell/*`. Tests show: tapping the current destination again pops it to its root; Back
  from the Search root goes to List; each destination keeps its stack.

- [ ] **M1-09 [L] List screen.** The stateless List as budgeted in section 9.3, including the sort sheet and the
  read-only variant.
  **Cases:** `watching` (rows: episodes to watch; nothing to watch, with the next episode; estimated with episodes
  to watch; estimated with nothing to watch; finished; unknown total; a long title that takes two lines together
  with lines 2 and 3), `planned` (with not-yet-aired rows for a known first-episode time, a full date and no date),
  `completed`, `sort-sheet`, `filtering` (the text field in the top app bar, a query with two matches),
  `refreshing`, `undo-snackbar`, `completed-snackbar` ("+1" reached the total: the row is gone and the snackbar
  says "Moved to Completed · Undo"), `offline-cached`, `loading`, `empty`, `error`, `signed-out`,
  `session-expired`, `read-only`.
  **Needs:** M1-07; the review needs M1-07a.
  **Check:** rubric pass on `list/*`.

- [ ] **M1-10 [L] Title page.** The stateless title page as budgeted in section 9.4.
  **Cases:** `airing-on-list` (episodes to watch; the content case), `not-on-list` (airing), `airing-estimated`
  (episodes to watch), `airing-estimated-caught-up` (estimated, nothing to watch), `airing-unknown` (aired count
  unknown), `finished`, `film` (the facts line without an episode count), `not-yet-aired` (the first episode's time
  is known), `not-yet-aired-date` (a full date, no time), `not-yet-aired-month` (a year and month only),
  `not-yet-aired-year` (a year only), `not-yet-aired-nodate`, `no-related`, `long-title`, `details-loading`,
  `no-score` (also no rank), `added-snackbar`, `completed-snackbar` (the stepper reached the total: the status
  button says Completed and the snackbar says "Moved to Completed · Undo"), `loading`, `error`.
  **Needs:** M1-07; the review needs M1-07a.
  **Check:** rubric pass on `title/*`, including the above-the-fold rule and the Related and Statistics sections in
  the `__full` images; in `airing-on-list` the Related strip shows both short strip labels and a two-line
  caption 1, and its cards have one height in every variant; in `ru-stress` and `ru-large`, «Продолжение» and
  «Предыстория» each stay on one line and «Запланировано» has no ellipsis.

- [ ] **M1-11 [L] Title sub-screens.** "Related: see all" and "Details" (section 9.9).
  **Cases:** `related/grouped`, `related/single`, `details/full`, `details/sparse`.
  **Needs:** M1-07; the review needs M1-07a.
  **Check:** rubric pass on `related/*` and `details/*`.

- [ ] **M1-12 [L] Edit sheet.** The stateless bottom sheet of section 9.5.
  **Cases:** `new-entry`, `existing`, `unknown-total`, `keyboard-open`, `over-total-error` (a typed value above the
  total, refused inline), `remove-confirm`, `discard-confirm`.
  **Needs:** M1-07; the review needs M1-07a.
  **Check:** rubric pass on `edit/*`.

- [ ] **M1-13 [A] Wiring and demo mode.** The app's wiring, and demo mode (section 6.4): a debug build without a
  client id runs on the fixtures in memory, writes included.
  **Needs:** M1-05.
  **Check:** a test shows that a "+1" made through the demo list reaches an observer of that list.

- [ ] **M1-14 View models, routes and the first APK.** List, title page and edit sheet working on demo data, with
  navigation between them.
  **Needs:** M1-08, M1-09, M1-10, M1-11, M1-12, M1-13, M1-02.
  **Check:** tests cover every event of the three view models; `tools/emu smoke` on the debug APK exits 0 and its
  device screenshots show the List with demo data.

- [ ] **M1-15 [O] Owner's look and calibration.** The owner installs the APK and checks: the app opens on the List
  with demo data; a row opens the title page; "+1" changes the progress and offers Undo; the phone's language and
  theme change the app. The owner then scores three more rendered screenshots with the rubric: List `watching` and
  Title `airing-on-list` in `en-light`, and List `watching` in `ru-large` as the example of the rubric's `ru-large`
  rule (its section 3), with each wrap marked as following from the font scale or not.
  **Needs:** M1-14.
  **Check:** `docs/calibration/` also holds the three images and their scores; the owner's remarks are filed as
  tasks.

## M2. Sign-in, the real list, the real title page

Goal: the owner signs in and uses their real list and real title pages (ARCHITECTURE sections 3, 7 and 9.2 to 9.5).

Acceptance: over three days of use, every list change made in the app is on the website and the reverse; no
sign-out happened that the owner did not cause; three title pages match myanimelist.net for related titles and
statistics; a `myanimelist.net/anime/{id}` link opens the title page with the List beneath it; the owner's findings
on the Russian strings so far are fixed.

Not to forget:

- The orchestrator moves the client id from the untracked `clientid.txt` into `~/.gradle/gradle.properties`. No
  agent reads that file or the value, and only the orchestrator may check that no recorded file contains it.
- Mappers are written only against recorded responses (section 11.1), so recording comes first, and the responses
  for the signed-in user need **the owner to sign in once** in a debug build and run the recorder. That needs the
  sign-in flow and the sign-in screen to work before the list is on real data. The recording adds and then removes
  one title that is not on the owner's list, and leaves the list as it was.
- The owner supplies, outside the repository, two user names for recording: one with a public list of more than
  1000 entries and one whose list is private. **No fixture contains a user name.**
- Related titles are requested with their media type, start date and the user's list status; caption 2 and the
  order of the Related strip need all three. One title is recorded with adult content on and off.
- The sign-in is tried by hand, including killing the app while the browser is open. This and the recorded
  responses settle items 1 to 3 of ARCHITECTURE section 13, which is corrected from them.
- Before the Russian relation names are written, they are compared with the names Shikimori shows, read from the
  site at that time and not from memory. The differences go to the owner; a term changes, in the glossary and the
  resources together, only on the owner's answer.

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
  rejected for their numbering (A4) and how many are only estimated (A5, A6). The counts settle item 4 of
  section 13.
- The rule that rows never move (section 9.3) is tested with airing data changing under a visible tab.

## M4. Reliable episode notifications

Goal: the notifications of ARCHITECTURE section 5 and the notification settings of section 9.8.

Acceptance: over seven days with notifications on, every entry of "Recently sent" matches an episode that had aired
by AniList's schedule for a title the owner was watching; no episode of a watched title with an exact count is
missing from it; there are no duplicates.

Not to forget:

- What is notifiable is written and tested in `:core` first, with every dailyAL failure case.
- The emulator run of section 5.2 forces Doze and restarts the device. It cannot use demo mode, whose data does not
  survive a restart, and a refresh must not overwrite the episode it plants. It measures the delay of the
  notification and whether the re-check reached the network, which settles item 5 of section 13.
- The "Mark watched" action is tested on an Android level before 12 as well as on a current one (N9).
- By hand on Android 13 or later: deny the permission, see "Blocked in system settings", grant it in the system
  settings, return, see the state updated, receive the test notification.

## M5. Search and browse

Goal: search, the four browse lists and the Recommendations sub-screen (ARCHITECTURE sections 9.6 and 9.9).

Acceptance: the owner finds a title by name and opens it; a title on their list shows its status in the results;
each browse list opens and loads further pages; a query of digits offers "Open title #<id>".

Not to forget:

- Searches of one, two and three characters are recorded, to learn the shortest query MAL accepts (item 3 of
  section 13).
- Typing does not send a request per keystroke.

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
  told apart from rate limiting (item 3 of section 13). Without a private user name from the owner, that stays
  open and the task says so.

## M7. Settings, polish and release 1.0

Goal: the Settings and About screens (ARCHITECTURE section 9.8), every screen on wide windows and with a screen
reader, and a signed release.

Acceptance: a tag `v0.9.0` produces a signed APK on a GitHub Release that installs next to the debug build and signs
in. After two weeks of daily use of `v0.9.x`, "Recently sent" meets the acceptance of M4 and no list change was
lost; the Russian strings of M7 are reviewed; then the orchestrator tags `v1.0.0`.

Not to forget:

- A wide-window pass over every screen, as one more variant, `en-wide`, of each content case: 840 × 600 dp and
  600 × 960 dp, the rail, the 640 dp content limit, landscape.
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
(ARCHITECTURE section 13, item 6).

| Milestone | Goal | Features | Sources |
|---|---|---|---|
| P1 | Manga and a fuller list | Manga lists, title pages, search, rankings and statistics, behind an Anime / Manga switch; related manga on anime pages; rewatching, tags, comments and priority in the edit sheet; more list sorts and a filter across statuses; an offline write queue with a visible pending state; pictures; opening and ending themes; deep links to manga pages | MAL only |
| P2 | A deeper title page | Votes per score; characters, voice actors and their pages; where to watch; trailers; the franchise in watch order; search filters, also from a title's genres; other users' avatar, "about" and favourites; more statistics charts | Jikan, AniList, MAL |
| P3 | Schedule and discovery | A weekly schedule of the user's airing titles as the fourth destination; a season browser; suggestions; more rankings; recently viewed titles; a per-title notification mute | Stored airing data, MAL |
| P4 | Community, read-only | Forum boards, topics and posts as the fifth destination; a title's discussions; reviews | MAL, Jikan |
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
