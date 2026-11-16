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

- [ ] **M3-11 AniList in the scenario panel.** The panel's "offline" fails AniList as it fails MAL, and one more
  switch fails AniList alone, on the demo and on the sandbox, so that section 2.3's row for AniList can be seen on
  a device.
  **Needs:** M3-08.
  **Check:** tests on the demo and on the sandbox: with the switch on, a refresh fails, the stored counts stay, a
  title added then shows the estimate and MAL's requests still work; a reset turns the switch off. The debug
  menu's screenshots are rendered with the switch.

- [ ] **M3-12 [O] Emulator run.** The orchestrator runs the app once on the emulator against the sandbox: a count
  rises as an air time passes with the network off; with AniList failing the stored counts stay; the preview
  build installed over a build of M2 keeps its list.
  **Needs:** M3-09, M3-10, M3-11.
  **Check:** the three observations, with the screenshots of each; a difference is filed as a task.

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

- [ ] **M4-01 [A] What is notifiable.** In `:core` only, as values and rules without a store. From the stored list,
  the stored schedules (what `AiringRepository.observeSchedules()` gives, and nothing fetched for a page, A12), the
  notification settings, the record of what was announced and an instant, one rule gives: the announcements that
  are due, one per title for its highest due episode (N6), each with the count still to watch and the episode "Mark
  watched" would mark (N9); the earlier announcements that no longer stand (N7); and the earliest notification time
  still to come (N8). Whether a count is exact and when an episode aired is the airing calculator's answer and is
  computed nowhere else (A1). N4 and N5 hold, a finale is still due after MAL has marked its show finished (A13),
  nothing is due more than three days after its notification time (N2), and no result depends on the time zone. The
  settings (on or off, also for planned titles, the delay) and the record are defined here: the episodes announced,
  and for N5 the moment from which each title is followed, with the rule that sets it at a sign-in, when
  notifications or planned titles are turned on, and when a title is added or changes status, also when a sync
  brings the change. The first run of a build with notifications over a list an earlier build stored counts as
  turning them on. A title that a list download left out and a later one brings back is the title it was: its
  moment and its announced episodes stay. The hand-off states, for the owner's approval, what that moment is for
  each event N5 names.
  **Needs:** nothing.
  **Check:** `./gradlew :core:test`: a test for each of N4, N5 (one per event it names, and the delay's case), N6,
  N7, A13 and the three days that fails without its rule; one test, named after it, for each failure of dailyAL
  that section 5.1 lists: a dropped, a completed, an on-hold and a removed title, an episode postponed after it was
  planned, an episode moved to another weekday, a show that has run for years and belongs to no season; applying
  the rule and recording its result at any sequence of instants announces each episode at most once (N1); a title
  left out by a download and brought back is announced neither twice nor late; a changed time zone changes no
  result; a rule test fails when code outside the calculator derives an aired count or an air time for a
  notification.

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

- [ ] **M4-03 Background writes and the edits a screen holds.** A "Mark watched" from a notification, or a sync,
  can change an entry while a screen holds an older view of it. An edit sheet opened before it and saved after it
  sends the values its user changed and no other (section 7.3): the action's episode stays unless the sheet changed
  the count, and then the count shown last wins (W1). A Retry or an Undo held in a message is applied to the entry
  as it is when tapped, and never puts back a value that the action or a sync has written since. The hand-off
  states, for the owner's approval, what a held Retry does then.
  **Needs:** nothing.
  **Check:** `./gradlew :core:test` in the list's contract suite, and tests of the edit sheet and of the shell's
  messages: with a sheet opened at 13 episodes and a `send` that marks the fourteenth, a save that changed the
  score alone leaves MAL with 14 and the score, and a save that set the count to 15 leaves 15; a Retry tapped after
  a `send` or a download changed the count leaves the count they wrote; an Undo of "+1" tapped after a `send` takes
  back its own step alone.

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

Not to forget:

- One statistics calculator in `:core` serves the owner and other users, tested against a list computed by hand.
- The answers for a public list, a private list and an unknown name are recorded, and settle how a private list is
  told apart from rate limiting (item 2 of section 13). Without a private user name from the owner, that stays
  open and the task says so.

- [x] **M6-01 Profile screen.**

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
