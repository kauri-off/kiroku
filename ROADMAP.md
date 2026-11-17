# Kiroku roadmap

Version 1.0 is milestones M1 to M7; "After 1.0" says what comes next and what is not built. What the result must
be is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); this file says in which order it is built and how each step
is accepted.

Only the milestone in progress is broken into tasks. A later milestone has a goal, an acceptance (what the owner
can do when it is finished) and the things its planner must not forget; it becomes tasks when it starts. A screen
of a later milestone may be built ahead of it, on fixture states. At the end of each milestone work stops and the
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
- [x] **M1-07 [A] Theme and components.**
- [x] **M1-08 App shell.**
- [x] **M1-09 List screen.**
- [x] **M1-10 Title page.**
- [x] **M1-11 Title sub-screens.**
- [x] **M1-12 Edit sheet.**
- [x] **M1-13 [A] Wiring and demo mode.**
- [x] **M1-14 View models, routes and the first APK.**

- [x] **M1-15 [O] Owner's look.**

## M2. Sign-in, the real list, the real title page

Goal: the owner signs in and uses their real list and real title pages (ARCHITECTURE sections 3 and 7).

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

- [x] **M2-07 Sign-in screen.**

- [x] **M2-08 [A] Sign-in flow.**

- [x] **M2-09 [A] Live graph and the choice of data source.**

- [x] **M2-10 [A] Recorder for answers that need only the client id.**

- [x] **M2-11 Sign-in in the app.**

- [x] **M2-12 [A] Recorder for answers that need the sign-in.**

- [x] **M2-13 [O] The owner signs in and records.**

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
  measures a list of 3000 entries on the phone. The owner uses the preview build for three days and reviews the
  Russian strings.
  **Needs:** M2-18, M2-20, M2-21, M2-23, M2-25, M2-26, M2-28, M2-29, M2-31.
  **Check:** the acceptance of M2; his findings are filed as tasks and the ones on Russian strings are fixed.

## M3. Episodes aired and episodes to watch

Goal: the List and the title page show aired counts, "to watch" and next episodes, and Watching is ordered "Up
next" (ARCHITECTURE section 4).

Acceptance: at the start, the aired counts of three airing titles, one of them on a break, agree with a source
other than AniList (the broadcaster's or a streaming service's schedule). Over seven days the owner finds no wrong
"aired" or "to watch" value on a title with an exact count.

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

- [ ] **M3-13 [O] The owner's seven days.** At the start the owner compares the aired counts of three airing
  titles of his list, one of them on a break, with the broadcaster's or a streaming service's schedule. He then
  uses the preview build for seven days. At their end the counts of M3-10 on his phone settle item 3 of section
  13.
  **Needs:** M3-12.
  **Check:** the acceptance of M3; item 3 of section 13 holds the counts; a wrong value is filed as a task with
  its reason from M3-10.

## M4. Reliable episode notifications

Goal: the notifications of ARCHITECTURE section 5 and their settings.

Acceptance: over seven days with notifications on, every entry of "Recently sent" matches an episode that had aired
by AniList's schedule for a title the owner was watching; no episode of a watched title with an exact count is
missing from it; there are no duplicates.

- [x] **M4-01 [A] What is notifiable.**

- [ ] **M4-02 [A] Notification engine.** In `:core` only, over ports as the list's and the airing's engines are: a
  store of M4-01's record and of the settings, the one alarm, and the notifications shown; with stores in memory
  and a contract suite. One run serves every trigger: it reads what is stored, applies M4-01's rule, posts what is
  due and records it, withdraws what no longer stands (N7) and leaves the alarm at the earliest coming notification
  time, or none (N8). Runs at the same time announce an episode once (N1), and a run cut off by the death of its
  process neither loses its episode nor announces it twice; the hand-off states how. A run started by the alarm
  with the device online first asks AniList again for the due titles alone, for a bounded few seconds: a postponed
  episode is planned again instead of announced, and an answer that fails or is late leaves the stored data in use
  (N3). While the process lives, a change of the list, of the airing data or of a setting, the delay alone
  included, is followed by a run (N8). Signed out, nothing is posted, what is posted is withdrawn, no alarm is left
  and the record is forgotten (S9); an expired session changes none of it (S6). For the screens the engine gives
  the settings and takes their changes, what comes next, and what was sent: each announcement with its title and
  episode, its air time, its notification time, when it was posted and how the re-check ended. Every run is
  reported with its trigger and what it did, for M4-15. Demo mode can run on it. The hand-off states the ports and
  the screens' surface for the orchestrator's approval before M4-07, M4-08 and M4-09 start.
  **Needs:** M4-01.
  **Check:** `./gradlew :core:test`, on virtual time: the contract suite holds a test for each of N1 (several runs
  at once; a refresh that lands between an air time and its notification; the delay changed; an alarm that never
  fired; a new engine over the same store, as after a restart or an update; a run cut off after each of its steps),
  N2 (each trigger announces what is overdue, and nothing three days late), N3 (postponed, failed, slower than the
  bound, offline), N7, N8 and S9, and the stores in memory pass it; a schedule fetched for a page reaches no run;
  the re-check asks AniList for the due titles and no other.

- [x] **M4-03 Background writes and the edits a screen holds.**

- [x] **M4-04 [A] One way in to MAL for background work.**

- [ ] **M4-05 Notification settings screen.** The stateless screen on fixture states, with its screenshot registry.
  It serves two needs: choosing (notifications on or off, also for planned titles, the delay), and finding out
  which link broke when a notification did not come, from what M4-02 and the device can tell; the record of what
  was sent, from which M4's acceptance is read, is on it. What it shows of that and how it is arranged is built
  once and shown to the owner (section 9). Its strings are in its own file in both languages, and a term it
  introduces enters the glossary.
  **Needs:** nothing.
  **Check:** the screen's screenshots are rendered in every variant, with cases for each state the device can be
  in, for nothing sent yet and for a long record; the render's accessibility check passes; `StringRulesTest`
  passes.

- [ ] **M4-06 [O] An emulator of Android 8 to 11.** The orchestrator gives the machine's emulator wrapper a second
  system image, of an Android version before 12, chosen for a run; the current image stays the default.
  **Needs:** nothing.
  **Check:** a debug build is installed and started on it and reports its API level; `:data`'s device tests pass on
  it.

- [ ] **M4-07 Database: the record and the settings.** M4-02's store of the record in the database, as a real
  migration (section 7.1), M4's only one; its settings in the source's preferences file. The record is in tables of
  its own, which no download, refresh or list write touches, and from now on every migration test starts from a
  database that holds announced episodes (N10).
  **Needs:** M4-02.
  **Check:** `./gradlew :data:testDebugUnitTest`: M4-02's contract passes over the store; the migration test takes
  a version 2 database that holds a list, titles and airing data to the new version and finds every row as it was;
  a test fails for a schema version whose migration is not run over announced episodes; a list download, an airing
  refresh and a record write made together lose nothing; a setting written is read by a new store over the same
  file, and one that cannot be read counts as never made and is reported. `./gradlew
  :data:connectedDebugAndroidTest` passes on an emulator.

- [ ] **M4-08 The notification on the device.** M4-02's port for the notifications shown, on Android's own: an
  episode's notification in its channel, one per title, grouped with the others; a tap opens the title page with
  the List beneath it (section 6.3), also when the app was not running; the action is delivered to this app alone
  and carries its title and the episode it marks; a later post for a title replaces the earlier one without
  alerting again; a withdrawal removes it, and the group's summary with the last one. Its text comes from the
  resources in the language in effect at the post, with the title in the preferred title language. What it says,
  its icon and how the group reads are built once and shown to the owner (section 9): a tool of the debug menu
  posts samples through the real channel, for one title, for several and for a title with more than one episode
  unwatched. Read first, on developer.android.com: `develop/ui/compose/notifications/create-notification` and
  `…/channels`, `develop/ui/views/notifications/group` and `…/navigation`.
  **Needs:** M4-02, M4-06.
  **Check:** tests: after posts, a replacement and withdrawals the notifications shown are exactly those the engine
  asked for, and the summary exists only while one does; the tap's stack is List, then Title; the action's intent
  names its title and episode and no other app can send or read it; under a Russian locale the text is Russian,
  with its plural forms. On an emulator of the current Android and on M4-06's: the samples are in the shade as the
  hand-off's screenshots show them, in both languages, and a tap on one with the app stopped opens the title page,
  from which Back reaches the List.

- [ ] **M4-09 [A] The alarm and the moments the app runs.** In `:app`: M4-02's alarm port on the system's alarm
  service, and everything that starts a run without a screen. The alarm is one-shot and is set so that it may fire
  while the device idles; it is exact when the system lets the app set exact alarms and inexact otherwise; the
  manifest declares `SCHEDULE_EXACT_ALARM` and not `USE_EXACT_ALARM` (section 5.1). A run is started by the alarm,
  by a restart of the device, by an update of the app, by a change of the exact-alarm access, and by the app being
  opened, which is also the first moment after a force-stop (N2, N8). Each keeps the process alive until its run
  has ended, within the time the system gives it. The task settles, for M4-11 and M4-12 too, how work without a
  screen is run and held, and brings into the build what that needs, with the reason in the pull request; nothing
  of it uses the network or the database when the process starts. No code opens a system settings screen or asks
  for a battery exemption. Where the system delays or drops an alarm (Doze, an app the user rarely opens, a
  force-stop), nothing works around it: the hand-off says, from the documentation and from the emulator, what is
  delayed and by how much, for M4-13 to tell the user. Read first, on developer.android.com:
  `develop/background-work/services/alarms`, `training/monitoring-device-state/doze-standby`,
  `topic/performance/appstandby` and `topic/performance/power/power-details`,
  `develop/background-work/background-tasks/broadcasts` and its `…/broadcast-exceptions`,
  `about/versions/14/changes/schedule-exact-alarms`, and the stopped state in
  `about/versions/15/behavior-changes-all`.
  **Needs:** M4-02, M4-06.
  **Check:** tests: each trigger starts one run and is held until it ends; the alarm set is the one the engine
  asked for, replacing the one before, and exact only with the access; a time-zone change sets no other alarm; the
  build's check of what the manifest lets another app start names every new component, and none is exported that
  the system's own broadcasts do not need. On an emulator of the current Android, each observation written down in
  the hand-off: with the device forced idle as the Doze page's testing section says, an alarm set by the app's own
  code fires and its run ends, after the delay measured; after a restart of the device the alarm is set again
  before the app is opened; after a force-stop nothing fires, and opening the app sets it again; the exact-alarm
  access taken away and given back changes the kind of alarm set, without the app being opened; the clock set past
  the alarm's time starts the run. On M4-06's emulator the alarm is set and fires.

- [ ] **M4-10 Live notifications.** The live graph's notifications are M4-02's engine over M4-07's store, M4-08's
  notifications and M4-09's alarm and triggers, each source with its own record, built at its first use and off the
  main thread. The sandbox runs on it, and the demo while its process lives, with its record in memory. A run by
  any trigger renews tokens that are due, through M4-04's way in (S7). A sign-out withdraws what is posted, clears
  the alarm and leaves no record (S9), also one the process did not finish. The run at the app's opening does not
  delay its first frame.
  **Needs:** M4-04, M4-07, M4-08, M4-09.
  **Check:** tests: reading the session or the list builds nothing of notifications; on the sandbox, signed in and
  synced, when its clock passes the air time of the title that airs soon after a reset, one notification is posted
  for that episode and the alarm is at the next coming time; a new graph over the same files posts nothing again
  (N1, N10); a build with notifications started over the files of one without them announces nothing that aired
  before (N5); a run started by the alarm renews tokens that are due, and with MAL's back-off set sends MAL
  nothing; a rule test fails when code that runs without a screen calls MAL other than through M4-04's way in;
  after a sign-out the database holds no record, nothing is posted and no alarm is set; started signed out, nothing
  builds an HTTP client; the tests of the app's start pass as before. The debug build passes the emulator's smoke
  run.

- [ ] **M4-11 Periodic background work.** Scheduled work keeps the app right while nobody opens it. Without a
  network it runs the engine (N2). With one it also renews the tokens (S7), downloads the list through M4-04's way
  in and refreshes the airing data of the tracked titles (sections 4 and 7.1), so that a status changed on the
  website and an episode AniList scheduled since are known before their notification time. It is scheduled once,
  survives a restart and an update, and scheduling it again does not double it. The hand-off states its interval
  for the owner's approval, and what the system does to it in Doze and for an app the user rarely opens, from the
  documentation and the emulator. Read first, on developer.android.com:
  `develop/background-work/background-tasks/persistent` with its `…/getting-started/define-work` and
  `…/how-to/manage-work`, and the two pages on Doze and on power restrictions that M4-09 names.
  **Needs:** M4-04, M4-10.
  **Check:** tests: one run with the sandbox online renews tokens that are due, downloads the list once and
  refreshes the stale airing data, and a title dropped at the sandbox's MAL since the last download is not
  announced; offline it announces what is overdue from stored data and sends no request; with MAL's back-off set,
  or the session expired, it sends MAL nothing and still refreshes the airing data; scheduled twice, it is there
  once. On an emulator: the work is scheduled after the app was opened once and again after a restart of the
  device, and made to run with the network off it posts the sandbox's overdue episode.

- [ ] **M4-12 "Mark watched" from the notification.** The action of M4-08's notification writes through
  `ListRepository.send` and M4-04's way in: it marks the episode its notification named, and only while that is the
  next unwatched one (N9). The write does not need the app to be open. Without a network, and during MAL's
  back-off, it waits and is tried again; when it cannot succeed, the notification is replaced by one that says so
  and opens the title. A `send` that throws counts as failed. After a success the title's notification follows the
  list (N7): it goes, or names the next episode to mark without alerting again. On Android 8 to 11 the work runs as
  the foreground service the system asks for there, which shows its own notification in a low-importance channel of
  its own; on Android 12 and later no foreground service starts and none is declared. The hand-off states, for the
  owner's approval, which answers end the waiting and after how long. Read first, on developer.android.com: the
  expedited work of `develop/background-work/background-tasks/persistent/getting-started/define-work`,
  `…/persistent/how-to/long-running`, `develop/background-work/services/fgs/restrictions-bg-start` and
  `…/fgs/service-types`, and how long a receiver may run in `develop/background-work/background-tasks/broadcasts`.
  **Needs:** M4-04, M4-06, M4-10.
  **Check:** tests: two taps, a tap after a sync brought the change, and a second try after MAL accepted the first
  raise the count once; a tap for an episode that is not the next unwatched one changes nothing; offline the write
  waits and is sent when the connection returns; during the back-off no request is sent before its end; tokens that
  are due are renewed before the write; a write that cannot succeed replaces the notification, and one that
  succeeded leaves the notification the list asks for. On an emulator of the current Android: the action tapped in
  the shade with the app stopped raises the sandbox's count by one, no foreground service of the app runs
  meanwhile, and the installed package has none enabled. On M4-06's emulator: the same tap raises the count, and
  the saving notification is there while the write runs and gone after it.

- [ ] **M4-13 Notification settings in the app.** M4-05's screen over M4-02's surface: its route and view model,
  opened from Profile's settings. The device's states (the notification permission, exact alarms, the battery
  restriction) are read when the screen is shown and again whenever the user comes back to it; each has a link to
  its system screen, opened only by the user's tap, and none asks for a battery exemption (section 5.1). A changed
  setting is followed at once (N5, N8). The test notification goes through the real channel. The screen says what
  the system delays or drops, as M4-09 found it. Read first, on developer.android.com:
  `develop/ui/compose/notifications/notification-permission`, the exact-alarm access in
  `develop/background-work/services/alarms`, and the restricted bucket in `topic/performance/appstandby`.
  **Needs:** M4-05, M4-10.
  **Check:** tests: turning notifications off withdraws what is posted and clears the alarm, and turning them on
  announces nothing from before; a changed delay moves the alarm; the states shown are the device's after the
  screen is shown again; no system screen opens without a tap; the screen's screenshots are rendered. On an
  emulator of the current Android: with the notification permission taken away the screen says so, its link opens
  the system's screen for this app's notifications, and after a grant there and a return the state is updated and
  the test notification arrives; the same for the exact-alarm access.

- [ ] **M4-14 The permission prompt.** On Android 13 and later the app asks for the notification permission with
  the system's own prompt, with no dialog of its own before it. When and on which screen it asks is built once and
  shown to the owner (section 9). A denial stops nothing but the showing: the record, the alarm and the runs go on,
  so that a later grant needs no further step. The hand-off says, from
  `develop/ui/compose/notifications/notification-permission` on developer.android.com and from the emulator, how
  often the system lets the prompt appear and what an install over an earlier build does.
  **Needs:** M4-13.
  **Check:** tests: below Android 13 nothing is asked; after a denial the alarm stays set, and a grant alone makes
  the next due notification appear; a later start does not ask again. By hand on an emulator of Android 13 or
  later: deny at the prompt, see in the notification settings that the system blocks them, grant in the system's
  settings, return, see the state updated, receive the test notification.

- [ ] **M4-15 Notifications in the debug menu.** What the emulator run and the seven days are read from. A section
  of the debug menu keeps the engine's reports of its runs across restarts and shows them: the trigger, the time,
  what was posted or withdrawn, the delay from a notification time to its post, and whether the re-check reached
  AniList. On the sandbox it plants an episode a chosen number of minutes ahead, kept in the sandbox's own files,
  so that it survives a restart of the device and every refresh brings it back unchanged, and it postpones a
  planted episode at the sandbox's AniList. No release build holds any of it.
  **Needs:** M4-10, M3-11.
  **Check:** tests: a planted episode is in the sandbox's answer at the same time after a new process and after a
  refresh, and at its new time once postponed; the section lists one line per run with its trigger and delay, also
  after a new process; its strings exist in both languages. The debug menu's screenshots are rendered with the
  section.

- [ ] **M4-16 [O] Emulator run.** The orchestrator runs the preview build once on the emulator against the sandbox,
  signed in; the demo cannot serve, as its data does not survive a restart. With episodes planted by M4-15: the
  device is forced idle before an air time and the notification is posted; the device is restarted before another
  and the notification is posted without the app being opened; after a force-stop nothing is posted until the app
  is opened; a planted episode postponed before its time is not announced then; with AniList failing an episode is
  announced from the stored data; "Mark watched" is tapped with the app closed, on the current Android and on
  M4-06's; the permission is denied, then granted in the system's settings; the preview build installed over a
  build of M3 keeps its list and announces nothing that aired before.
  **Needs:** M4-11, M4-12, M4-14, M4-15, M3-12.
  **Check:** each observation, with its screenshot or its lines of M4-15's log; item 4 of section 13 is rewritten
  from the measured delay of the notification in Doze and from whether the re-check reached the network; a
  difference is filed as a task.

- [ ] **M4-17 [O] The owner's seven days.** The owner uses the preview build for seven days with notifications on,
  as he uses his phone otherwise. At their end he compares what the app lists as sent with AniList's schedule of
  the titles he was watching.
  **Needs:** M4-16, M3-13.
  **Check:** the acceptance of M4; a missing, a duplicate or a wrong notification is filed as a task with its lines
  of M4-15's log and the states the notification settings showed.

## M5. Search and browse

Goal: search, the four browse lists and the Recommendations sub-screen.

Acceptance: the owner finds a title by name and opens it; a title on their list shows its status in the results;
each browse list opens and loads further pages; a query of digits offers "Open title #<id>".

Not to forget:

- Searches of one, two and three characters are recorded, to learn the shortest query MAL accepts (item 2 of
  section 13).
- Typing does not send a request per keystroke.
- Results that arrive page by page are held as a `StateFlow` of the pages loaded.

- [x] **M5-01 Search screen.**

## M6. Profiles and statistics

Goal: the owner's profile, another user's profile and list, and the statistics sub-screens.

Acceptance: the owner's completed count and mean score equal those on their MAL profile page ("Days watched" may
differ, because MAL counts rewatches); another user's profile opens by name and by a `myanimelist.net/profile/…`
link, also for a list of several thousand entries; a private list and an unknown name each say what they are. The
owner's findings on the Russian strings of M3 to M6 are fixed.

- [x] **M6-01 Profile screen.**

- [x] **M6-02 Statistics calculator.**

- [x] **M6-03 Recorded answers of another user's list.**

- [x] **M6-04 [A] Other users' lists.**

- [ ] **M6-05 The signed-in user's profile.** In `:data`: what MAL gives of the signed-in user beside the list, as
  `UserProfile` holds it, read as that user through the session (S5), and kept in the database (section 7.1) by a
  real migration, in a table of its own that no download, refresh or list write touches. The statistics in MAL's
  answer are not kept: the app computes its own (section 2.2). What is stored is the last whole answer: a refresh
  that failed changes nothing, and a value that cannot be read is left out and reported (section 2.3). It stays
  readable with the session expired (S6) and is forgotten when the list's owner changes and at a sign-out (S9). It
  is the own-profile part of `UserRepository`, and M6-10 puts it into the graph.
  **Needs:** nothing.
  **Check:** `./gradlew :data:testDebugUnitTest`: the migration test takes a database of each earlier version that
  holds a list, titles and airing data, and announced episodes once a version has them (N10), to the new one and
  finds every row as it was; the profile read from the sandbox is stored, observed, and read by a new store over
  the same file; a failed refresh leaves what was stored; a new owner sees nothing of the last one's; a rejected
  token causes one refresh and one retry; with the session expired the stored profile is read and MAL is not
  asked. Where the owner's recordings are: the mapper reads his recorded profile, and a failure names no value of
  it. `./gradlew :data:connectedDebugAndroidTest` passes on an emulator.

- [ ] **M6-06 Profile links.** A link to a user's profile on MAL opens that user's profile in the app (section
  6.3). The address is read in one place, with the title's link, into the user it names, the name decoded and
  otherwise as the link spelled it; the same reader takes an address the person pastes where the app asks for a
  name, there also without its scheme. The manifest claims a profile's page as it claims a title's, and an address
  the filter matched that the app does not open goes on to the browser. A link opens its profile signed out too,
  when the app was not running, and whatever screen was shown; Back from it reaches the List and does not leave the
  app. Which destination's stack holds the profile is built once and shown to the owner (section 9).
  **Needs:** nothing.
  **Check:** tests: the reader takes a profile's page on either host, with or without a trailing slash, and
  refuses another scheme, another host, a port, user info, a sub-page, an encoded slash and an empty name; the
  manifest's filter and the reader agree on a table of addresses; a profile's link reaches the app once, a link
  the system replays from the recent tasks does not, and any other address goes to the browser; the stack a link
  builds ends at the List; a title's link opens its title as before. On an emulator of the current Android, with
  the debug build's links allowed and the app stopped: a link to the demo's public user opens that profile, Back
  reaches the List, and a link to its private user says the list is private.

- [ ] **M6-07 Sandbox: other users.** The sandbox's MAL answers for other users' lists in the recorded shapes: a
  public user whose list has several thousand entries, made from the sandbox's titles and given in pages of the
  size asked; a user whose list is private; and nobody else. It answers them to the client id alone, signed in or
  not, and its failures (offline, slow) apply to them. Its profile of the signed-in user is held to the owner's
  recording.
  **Needs:** nothing.
  **Check:** `./gradlew :data:testDebugUnitTest`: given the entries of the recorded public pages, the sandbox's
  pages hold the same titles in the same order, values and offsets; its answers for the private user and for an
  unknown name are the recorded ones; `MalLists` reads the list of several thousand whole, each entry once; a
  request for another user's list that carries a token is answered as one without. Where the owner's recordings
  are: the sandbox's profile has the fields of his recorded one, each of the same kind.

- [ ] **M6-08 Another user's list from MAL.** In `:data`: M6-04's port over MAL's list of a named user. A part is
  a page, asked one at a time, and the entries so far follow each. The name goes to MAL as one segment of the
  address whatever it holds, with the client id alone, and never as MAL's word for the signed-in user. The outcomes
  are M6-03's; a page that fails fails the read with that page's failure, and no page after it is asked; what was
  repaired is reported once the list is whole (section 2.3). Whether two names are one user is answered from what
  M6-03 recorded, and the sandbox answers a name in another letter case and a name no account can have as
  recorded.
  **Needs:** M6-03, M6-04, M6-07.
  **Check:** `./gradlew :data:testDebugUnitTest`: M6-04's contract passes over it against the sandbox; the list of
  several thousand is given whole, in parts, with the entries so far after each; a private list and an unknown name
  are their outcomes on the recorded answers; a name that holds a slash, a question mark, a space, letters outside
  Latin or MAL's word for the signed-in user reaches MAL as one segment and without a token, signed in too; a
  refusal half-way is a refusal after which background work backs off; `SendRulesTest` passes.

- [ ] **M6-09 The Profile on real state.** The view models and the route of the Profile as `main` has it, in place
  of its stand-in, changing nothing of what it shows. The signed-in user's profile: what M6-05 keeps of them, and
  the statistics M6-02's calculator gives over the stored list, so that they follow an edit at once and work
  offline (section 7.2). Another user's: M6-04's keeper, with its progress, a private list and an unknown name
  each as what it is, and the failures of sections 2.3 and 6.2; a pull reads the list again. It opens by a name
  the person gives and by M6-06's link, signed out too. The signed-in user's own name, opened as another's, is
  their own profile; which names are one user is the port's answer, here and among the names opened earlier, which
  hold only names that turned out to be users. No state assumes which users a source describes beyond their list.
  Nothing is read before a screen is shown, and a rotation or a new process shows the same profile and typed text
  (section 6.2). The account keeps its placeholder until M6-10.
  **Needs:** M6-02, M6-04.
  **Check:** `./gradlew :app:testDebugUnitTest`, on the demo: the own profile shows the calculator's values over
  the demo's list, and a "+1" that completes a title changes them with no refresh; offline they show from the
  stored list, and the refresh that failed is a message; another user's profile shows the progress and then the
  statistics of the list of several thousand; the private user and an unknown name each show their own state, and
  neither is asked again by itself; a read that fails with nothing held is the error state with Retry, and with a
  list held the list stays; the Profile and a second screen of one user cause one read; nothing is read before the
  screen counts as shown; after a new process the same profile and the typed name are there; signed out, another
  user's profile opens; opening the own name shows the own profile. The Profile's screenshots are rendered and
  differ from `main`'s in nothing.

- [ ] **M6-10 Live profiles.** The live graph's profiles are M6-04's keeper over M6-08's port, with M6-05's store,
  built at their first use and off the main thread; the sandbox runs on them, and the Profile is M6-09's route in
  every build. A sign-out leaves no profile in the database and no list held (S9), also when nothing had asked for
  them, and they are forgotten where the user's other stores are. Another user's list is read without the tokens,
  signed out and with the session expired too. The debug menu names the users the demo and the sandbox answer for.
  **Needs:** M6-05, M6-08, M6-09.
  **Check:** tests: reading the session or the list builds nothing of profiles, and started signed out nothing
  builds an HTTP client before a profile is opened; on the sandbox, signed in and synced, the own profile's
  completed count and mean score are those of the sandbox's own profile answer; its user with several thousand
  entries opens by name and shows the statistics of the whole list; its private user and an unknown name each say
  what they are; with the sandbox offline the own profile shows from the database; a new graph over the same files
  shows the profile without a request; after a sign-out the database holds no profile and no list is held; the
  tests of the app's start pass as before. The debug build passes the emulator's smoke run, and the debug menu's
  screenshots are rendered.

- [ ] **M6-11 [O] The owner chooses the Profile.** On his phone, from the prototypes, the owner chooses the
  composition of the Profile, and with it where the statistics and another user's list are shown.
  **Needs:** nothing.
  **Check:** one composition is chosen; M6-12, M6-13 and M6-14 start from its prototype.

- [ ] **M6-12 The Profile as chosen.** The Profile's stateless screen in the chosen composition, for the signed-in
  user and for another, on fixture states with its screenshot registry, and drawn from M6-09's state in the app.
  It is built once, on real state, and shown to the owner (section 9), within sections 6.2, 7.2, 8 and 10; Settings
  still opens from it (section 6.3). Names opened earlier, where it offers them, are kept across restarts with the
  person's other choices and forgotten at a sign-out. Its strings are in the Profile's file in both languages, and
  a term it introduces enters the glossary.
  **Needs:** M6-09, M6-11.
  **Check:** the screen's screenshots are rendered in every variant, with a case for each state M6-09 can give:
  loading, a list being read with and without a total, a private list, an unknown name, each failure, signed out,
  the session expired, nothing on the list, a list of several thousand; the render's accessibility check passes;
  `StringRulesTest` and M6-09's tests pass. The owner looks at it on his phone, on the demo and on his account.

- [ ] **M6-13 Statistics views.** What the chosen composition shows of the statistics beyond the Profile's own
  page, for the signed-in user and for another: stateless, on fixture states, each with its screenshot registry,
  its route and its strings in a file of its own. Every number is the calculator's; one it lacks is added to the
  calculator (M6-02). They are drawn with stock components and no chart library (sections 6.4 and 8.4); a genre and
  a media type read as section 10 says. Built once, on real state, and shown to the owner (section 9).
  **Needs:** M6-09, M6-11.
  **Check:** the screenshots of each view in every variant, with cases for nothing scored, a single value, a list
  of several thousand and the longest list of genres; the render's accessibility check passes; tests: the values
  shown are the calculator's over the fixtures' list; opened from another user's profile they are that user's, from
  the read their profile made; M6-02's rule test and `StringRulesTest` pass.

- [ ] **M6-14 Another user's list in the app.** The list of a named user, where the chosen composition has it,
  from the read their profile made and no second one. It cannot be written: nothing on it edits, steps or removes,
  and no code of it holds the list's writer. A row opens the title's page, which shows the person's own entry. Its
  orders are `:core`'s, and an entry with a status the app does not know is on it (section 7.1). Nothing of another
  user's list is sent to AniList (section 2.1). Built once, on real state, and shown to the owner (section 9).
  **Needs:** M6-09, M6-11.
  **Check:** tests: opened after the profile, the list causes no request; no event of the screen reaches a write;
  a row opens its title with the person's own status; with a list of several thousand entries held, the work of
  the frames of a fling is counted in the style of `ListFrameWorkTest`; opening the list asks AniList for nothing;
  an entry of an unknown status is shown. The screenshots are rendered with a case for the longest tab and for an
  empty one, and the render's accessibility check passes.

- [ ] **M6-15 [O] Emulator run.** The orchestrator runs the preview build once on the emulator against the
  sandbox, signed in: the own profile shows the counts of the sandbox's list; the user with several thousand
  entries is opened by name, and the longest part of their list is scrolled to its end; with the app stopped and
  its links allowed, a link opens that profile and Back reaches the List; the private user and a mistyped name each
  say what they are; with the network off the own profile still shows, and another user's offers Retry, which works
  once the network is back; the preview build installed over a build without M6-05's migration keeps its list, its
  airing data and what else that build stored.
  **Needs:** M6-06, M6-10, M6-12, M6-13, M6-14.
  **Check:** each observation, with its screenshot; a difference is filed as a task.

- [ ] **M6-16 [O] The owner's check.** With the preview build on his account, the owner compares the Profile's
  completed count and mean score with his profile page on MAL; opens, by name and then by a link from another app
  once he has allowed the app's links in Android's settings, a user whose public list has several thousand entries;
  opens a user whose list is private and a name nobody has; and reviews the Russian strings of M3 to M6.
  **Needs:** M6-15.
  **Check:** the acceptance of M6; a difference is filed as a task with what the screen said, and his findings on
  the Russian strings are fixed.

## M7. Settings, polish and release 1.0

Goal: the Settings and About screens, every screen on wide windows and with a screen reader, and a signed
release.

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
- A changed setting reaches the screens that are open: title language and adult content are read where they are
  shown, not copied when a view model is made.
- `CONTRIBUTING.md` tells a fork to set its own application id and sign-in redirect beside its own client id: two
  installed apps that claim one redirect split the sign-ins.
- Sign-out clears the image caches with everything else stored about the user (S9).

---

## After 1.0

This section accounts for every other dailyAL feature: it is planned, or it is not built, with the reason. A
feature the owner wants back moves from "Not built" to a planned milestone. The milestones are built in the order
P1 to P5. The sources other than MAL are unchecked (ARCHITECTURE section 13, item 5).

| Milestone | Goal | Features | Sources |
|---|---|---|---|
| P1 | Manga and a fuller list | Manga lists, title pages, search, rankings and statistics; related manga on anime pages; rewatching, tags, comments and priority in the edit sheet; more list sorts and a filter across statuses; an offline write queue with a visible pending state; the cover opened full-screen, with the title's other pictures to swipe through; opening and ending themes; deep links to manga pages | MAL only |
| P2 | A deeper title page | Votes per score; characters, voice actors and their pages; where to watch; trailers; the whole franchise in watch order, beyond the chain of sequels; search filters, also from a title's genres; other users' avatar, "about" and favourites; more statistics charts | Jikan, AniList, MAL |
| P3 | Schedule and discovery | A weekly schedule of the user's airing titles; a season browser; suggestions; more rankings; recently viewed titles; a per-title notification mute | Stored airing data, MAL |
| P4 | Community, read-only | Forum boards, topics and posts; a title's discussions; users' reviews of a title | MAL, Jikan |
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
