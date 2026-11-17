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

- [x] **M4-02 [A] Notification engine.**

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

- [x] **M4-06 [O] An emulator of Android 8 to 11.**

- [x] **M4-07 Database: the record and the settings.**

- [x] **M4-08 The notification on the device.**

- [x] **M4-09 [A] The alarm and the moments the app runs.**

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

- [x] **M6-05 The signed-in user's profile.**

- [x] **M6-06 Profile links.**

- [x] **M6-07 Sandbox: other users.**

- [x] **M6-08 Another user's list from MAL.**

- [x] **M6-09 The Profile on real state.**

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
| P2 | A deeper title page | Characters, voice actors and their pages; where to watch; trailers; the whole franchise in watch order, beyond the chain of sequels; search filters, also from a title's genres; other users' avatar, "about" and favourites; more statistics charts | AniList, MAL, one not chosen yet |
| P3 | Schedule and discovery | A weekly schedule of the user's airing titles; a season browser; suggestions; more rankings; recently viewed titles; a per-title notification mute | Stored airing data, MAL |
| P4 | Community, read-only | Forum boards, topics and posts; a title's discussions; users' reviews of a title | MAL, one not chosen yet |
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
