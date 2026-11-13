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

- [ ] **M1-15 [O] Owner's look and calibration.** The owner installs the APK and checks: the app opens on the List
  with demo data; a row opens the title page; "+1" changes the progress and offers Undo; the phone's language and
  theme change the app. The owner then reviews three more rendered screenshots with the rubric: List `watching` and
  Title `airing-on-list` in `en-light`, and List `watching` in `ru-large` as the example of the rubric's `ru-large`
  rule (its section 3), with each wrap marked as following from the font scale or not.
  **Needs:** M1-14.
  **Check:** `docs/calibration/` holds the three images and their reviews; the owner's remarks are filed as
  tasks.

## M2. Sign-in, the real list, the real title page

Goal: the owner signs in and uses their real list and real title pages (ARCHITECTURE sections 3, 7 and 9.2 to 9.5).

Acceptance: over three days of use, every list change made in the app is on the website and the reverse; no
sign-out happened that the owner did not cause; three title pages match myanimelist.net for related titles and
statistics; a `myanimelist.net/anime/{id}` link opens the title page with the List beneath it; the owner's findings
on the Russian strings so far are fixed.

- [ ] **M2-01 [O] Client id on the machine.** The orchestrator moves the MAL client id from the untracked
  `clientid.txt` into `~/.gradle/gradle.properties` as `kiroku.malClientId`. No agent reads that file or the value.
  From then on every local build carries the id, so an agent's emulator run passes `-Pkiroku.malClientId=` unless
  its task says otherwise: only the owner and the orchestrator drive the real MAL through the app.
  **Needs:** nothing.
  **Check:** `grep -c '^kiroku\.malClientId=.' ~/.gradle/gradle.properties` prints 1 (a count, so no value is
  shown); `git check-ignore clientid.txt` prints the file name; `git status` is clean.

- [ ] **M2-02 [O] Names for recording.** The owner writes two user names into the same file, outside the
  repository: `kiroku.recordPublicUser`, a user with a public list of more than 1000 entries, and
  `kiroku.recordPrivateUser`, a user whose list is private. He registers the preview build's redirect URI with MAL
  if he decides so (section 3.1).
  **Needs:** nothing.
  **Check:** `grep -c '^kiroku\.record' ~/.gradle/gradle.properties` prints 2.

- [x] **M2-03 [O] Frame for the first wave.**

- [ ] **M2-04 [A] Network foundation.** One HTTP client for MAL, shared by every caller and later by the image
  loader, built on first use and never on the main thread. MAL's two hosts (the API and the sign-in) are
  parameters, so a test or a stand-in can take their place. Every failure of a request is a typed error of section
  2.3 and never an exception outside `:data`; statuses are told apart by their code alone, since no answer's shape
  is known yet. Refused requests make background work back off; a read may be repeated, a write is sent once
  (section 6.4). Tokens and the client id reach no log.
  **Needs:** M2-03.
  **Check:** tests against a local server: each MAL row of section 2.3's table gives its error; a write whose
  connection drops is sent once; the log of a request carrying both credentials holds neither; a release build
  with R8 still passes its guards.

- [ ] **M2-05 [A] Tokens and session.** S4 to S8. The tokens have one owner per process, through which every
  authorised request goes; the session is signed out, signed in or expired, and has no value before the token
  store was read. An authorised request that ends rejected is the outcome "session expired", never an exception.
  The token answer is read as MAL's authorization reference prints it; which answers reject a refresh is settled
  on recorded ones in M2-11. The app takes no part in cloud backup or device transfer. S7's rule (renew tokens
  older than a set age) is here and runs when the app starts work; its periodic trigger comes with M4's
  background work.
  **Needs:** M2-03.
  **Check:** tests: ten requests rejected at once cause one refresh and one retry each (S5); a rejected refresh
  ends in expired with the tokens gone, and a temporary failure or a success without both tokens keeps them (S4,
  S6); a write of the tokens cut at any point leaves the old or the new pair readable, and a store that cannot be
  read is signed out (S8); a manifest test for the backup rule.

- [ ] **M2-06 [A] List engine for a database.** In `:core` only. The engine takes the session: signed out it
  shows and sends nothing; expired it keeps the list readable, sends nothing and fails a write without Retry (S6,
  W2); a new sign-in is a new list, and a sign-out clears the stored list through the engine (S9); it does
  nothing before the session is known. The time of the last successful sync is stored, W5's restore clears it,
  and the list source can say it. A title's data and an entry's values are apart at the store's port: title data
  enters the store only from a download and with a title new to the store, an edit writes values alone, and a
  sync that changes nothing writes nothing. A store that cannot be read is tried three times in a row, not three
  times in all; an exception while a queued edit is sent does not leave its title out of later syncs. The
  hand-off states the port for the orchestrator's approval before M2-16 starts.
  **Needs:** M2-03.
  **Check:** `tools/gw :core:test`: the contract suite holds a test for each sentence above that fails on the
  engine of today, and the in-memory store and demo mode pass it.

- [ ] **M2-07 [L] Sign-in screen.** The stateless screen of section 9.2 on fixture states: default, no client id,
  the sign-in being completed, failed; no view model, no network.
  **Needs:** nothing.
  **Check:** the rubric on `signin/*`.

- [ ] **M2-08 [A] Sign-in flow.** S1 to S4: the authorisation with PKCE in the user's browser, the redirect URI
  as its own constant per build type (section 3.1), the redirect accepted once, only for a sign-in this app
  started a short time ago and only with its `state`, and the exchange, which outlives the screen and the
  process. The flow's progress and its result are state that is read and then acknowledged.
  **Needs:** M2-04, M2-05.
  **Check:** tests: a wrong, missing, old and repeated redirect each end in "Sign-in failed" (S3); an answer that
  is not a success or lacks a token signs nobody in (S4); new instances over the same files, as after a process
  death, complete a sign-in from its redirect (S2); a manifest test that each build type's filter carries its own
  URI and that nothing else new is exported. `tools/emu run`: the redirect URI sent to the app while it is not
  running starts it and ends in "Sign-in failed".

- [ ] **M2-09 [A] Live graph and the choice of data source.** The live graph is built over `:data` with the real
  session, one file per area (session, list, titles), so a later task replaces one area without touching
  another. Nothing of the network or the database is built before its first use. Debug and preview builds choose
  their source: the real account (the default when the build has a client id), the demo, and later the sandbox;
  the choice is kept and applied when the process starts. Each source has its own stores and the demo reaches no
  MAL host. No test depends on whether the build has a client id. The release reporter logs an exception's type,
  never its message, which could quote the list. The debug menu takes its sections from one file each.
  **Needs:** M2-05, M2-06.
  **Check:** tests: with a client id a debug build starts on the real account and, after the choice, on the demo;
  reading the session builds neither the HTTP client nor a database; the demo source builds no HTTP client at
  all; the reporter's output for an exception whose message holds a title does not hold it; the tests that
  skipped themselves with a client id are gone or run. The release guards pass.

- [ ] **M2-10 [A] Recorder for answers that need only the client id.** The build task of section 11.1, run by
  agents through `tools/gw`: a list of requests kept in the repository, the field sets written once for the
  recorder and the clients alike, and a sanitiser that replaces the two user names wherever MAL repeats them and
  keeps at most ten entries of a list page. First recordings: titles (airing, finished, a film, not yet aired, one
  with no related titles, one with an unknown episode count, one adult title with adult content on and off); an
  unknown title; the public list in three pages, the private list and an unknown user name; a request with an
  invalid token and a refresh with an invalid refresh token.
  **Needs:** M2-01, M2-04; the three list answers need M2-02.
  **Check:** `tools/gw :data:<the task>` writes the files; with `-Pkiroku.malClientId=` it fails with "no client
  id" before any request; the task is in no build or check; tests: a planted answer leaves the sanitiser without
  its user name, and no header is written; every file among the recorded answers is named by a recorder's list.
  Only the orchestrator checks that no file holds the client id:
  `grep -rlF "$(sed -n 's/^kiroku\.malClientId=//p' ~/.gradle/gradle.properties)" data/src` prints nothing.

- [ ] **M2-11 Sign-in in the app.** The sign-in route and its view model join M2-07 to M2-08: the List's
  signed-out state, the session banner and a title page lead to it, "Browse without an account" goes to Search, a
  finished sign-in ends on the List. What the view model hands its route (signed in, a redirect received) is
  state the route acts on and acknowledges, as `ShellMessages.editRequest` is. Debug and preview builds sign out
  from the debug menu until Settings exist. The session's answers agree with the recorded invalid token and
  rejected refresh. CI's debug build step gets the secret `MAL_CLIENT_ID` back.
  **Needs:** M2-07, M2-08, M2-09, M2-10.
  **Check:** tests: signed out, "Sign in" opens the screen and a finished sign-in leaves the stack at the List; a
  result is acted on once across a recreated activity; a build without a client id shows the screen's
  no-client-id form; the recorded rejection ends the session and the recorded invalid token leads to one refresh.

- [ ] **M2-12 [A] Recorder for answers that need the sign-in.** A tool of debug and preview builds, opened from
  the debug menu, that sends a fixed list of requests through the app's own owner of the tokens and stores the
  answers in the app's storage: the profile with statistics; the own list in pages; and, on one title that is not
  on the list, an add, a count, a count above the total, a completion, each date set and cleared, a score set and
  cleared, a value MAL should refuse, the entry read back after each, the removal and a second removal. The list
  covers what M2 to M6 need from a signed-in user, so one session is enough. The owner's list is as it was
  afterwards, also after an interrupted run. Every value of the profile is replaced unless it is on a list of
  harmless ones; list pages keep ten entries; no header and no token is stored, only the lifetime MAL gave the
  access token.
  **Needs:** M2-09, M2-10.
  **Check:** tests against a local server with planted personal values: none is in the files; a run interrupted
  after the add removes the title at the next start; a title already on the list is refused before any write.
  The release guards show no release build holds the tool, and no other app can start it.

- [ ] **M2-13 [O] The owner signs in and records.** With the debug build: he signs in; signs in once more with
  the app killed while the browser is open; runs the recorder on a title that is not on his list. On the website
  he completes a title with an empty start date, steps a Completed title below its total and completes a title
  that has a finish date, and says what MAL did (W7). The orchestrator pulls the files, reads them for anything
  personal, checks them for the client id, commits them, and corrects section 3.1 and items 1 to 3 of section 13.
  **Needs:** M2-11, M2-12.
  **Check:** the recorded answers of M2-12 are committed; `git grep` for the owner's and the two users' names
  finds nothing; his list on myanimelist.net is as it was; a difference from W7 is filed as a task.

- [ ] **M2-14 Title client and mappers.** `anime/{id}` to the domain model, and the mapper of a title's summary
  that the list uses too. MAL's zero and empty values become absent (an episode count or length of 0, an empty
  English or Japanese title or synonym, an empty synopsis), with a recorded answer holding each; genres are null
  when an answer has none; a code the app does not know is kept as its code. A related title carries its media
  type and start date; its list status is read from the stored list. Both picture sizes MAL gives are kept.
  **Needs:** M2-10.
  **Check:** `tools/gw :data:testDebugUnitTest`: mapper tests on recorded answers only, one per rule above; every
  related title of the recorded airing title has a media type and a start year. Items 2 and 3 of section 13 are
  corrected from the answers, the adult-content one included.

- [ ] **M2-15 List client: reading.** The list download of `RemoteList`: it asks with `nsfw=true`, reads the
  pages in an order edits do not change, and fails when any page fails. A list score of 0 is no score; dates may
  be partial. It serves the signed-in user and, in M6, a named one.
  **Needs:** M2-05, M2-14, and the list answers of M2-10.
  **Check:** tests on the recorded pages behind a local server: three pages give every entry once; a failed
  middle page fails the download; the request carries `nsfw=true` and the order.

- [ ] **M2-16 [A] Database: the titles and the list's store.** The database of section 7.1 for titles (summary,
  details, related titles) and the list (shown values, confirmed values, the unconfirmed mark, the sync time, the
  sign-in the list belongs to),
  with the port M2-06 settled. A title's refresh and a list write do not overwrite each other, and a stored
  value is never replaced by a missing one. The list is one shared flow, rows are mapped off the main thread, an
  entry that did not change stays the same object, and one entry can be observed by itself. Version 1 of the
  schema is what M2-33 installs; from then on every change is a migration.
  **Needs:** M2-06.
  **Check:** `tools/gw :data:testDebugUnitTest`: `ListEngineContract` passes over the store; a migration test
  harness exists and a test fails when a destructive fallback is configured; with 3000 entries, a write of one
  emits a list whose other entries are the same objects; a title refreshed and then edited on the list keeps the
  refreshed data, and the reverse; a summary without genres leaves stored genres in place.

- [ ] **M2-17 Anime repository.** The titles of section 7.1 over M2-14 and M2-16: a page shows what is stored,
  refreshes what is stale, and keeps what is stored when a refresh fails. A title reached from a related row has
  that row's data from the first frame.
  **Needs:** M2-14, M2-16.
  **Check:** tests: stored details are shown without a request while fresh; a failed refresh changes nothing
  stored and is reported; a refresh of the details does not lose a value the summary had.

- [ ] **M2-18 Translated MAL values and the genre table.** Both languages for every media type, source, age
  rating, relation, season, airing status and genre MAL uses, the genres collected by the recorder from enough
  titles to hold them all, adult ones included. Before the Russian relation names are written they are compared
  with the names Shikimori shows, read from the site at that time; the differences go to the owner, and a term
  changes, in the glossary and the resources together, only on his answer.
  **Needs:** M2-10.
  **Check:** a test that every code and genre in the recorded answers has both translations, and that an unknown
  one falls back as section 10 says; the pull request holds the comparison (relation, glossary, Shikimori) or
  says the site could not be read.

- [ ] **M2-19 List client: writing.** The rest of `RemoteList`: one entry read, set and removed, the answer
  being the entry as MAL has it afterwards; removing a title that is not on the list is a removal. The client is
  held to `RemoteList` by tests on recorded answers. The recordings settle what a write answers with, whether it
  can clear a date and how MAL takes a count above its total; whatever differs from W3, W7 or W9 goes to the
  owner in the hand-off and is not worked around.
  **Needs:** M2-13, M2-15.
  **Check:** tests on the answers of M2-13, one per request the recorder made; the reading mappers of M2-15 pass
  on the owner's own pages. Items 2 and 3 of section 13 are corrected.

- [ ] **M2-20 Sandbox.** A stand-in for MAL in debug and preview builds, behind the real network layer: it keeps
  a list as the website would and answers in the recorded shapes, sign-in and refresh included, so that
  behaviour is tried without touching an account. It is the third data source of M2-09 and takes the scenario
  panel's failures. No release build holds it or a recorded answer.
  **Needs:** M2-09, M2-16, M2-19.
  **Check:** `ListEngineContract` passes over the real client, the stand-in and the database; the stand-in,
  given the state of a recorded answer, gives that answer back; in the sandbox no request leaves the device; the
  panel's "session expired" ends in the banner through a real rejected refresh.

- [ ] **M2-21 The real list.** The live graph's list is the engine over M2-19 and M2-16; `InMemoryLocalList`
  moves to the test fixtures. Signing out removes the tokens and everything stored about the user (S9). Until M3
  there is no airing data: no screen shows an airing line, and Watching is ordered by the last update.
  **Needs:** M2-11, M2-16, M2-19.
  **Check:** tests over recorded answers behind a local server: a sign-in fills the list; a sign-out leaves no
  entry and no token, and the next sign-in shows nothing of the one before; with the airing source empty, no
  row and no title page shows an airing line. A release build holds no in-memory store.

- [ ] **M2-22 Screens refresh by themselves.** A screen refreshes what is stale when it is shown, and a screen
  left in the background past its state's stop timeout counts as shown again (section 7.1). A screen that shows
  a failure tries once more when the connection returns. On demo data, where the scenario panel's "offline" is
  the connection.
  **Needs:** M2-06, M2-09.
  **Check:** view-model tests for the List, the title page and its sub-screens: a fresh screen shown again asks
  nothing, a stale one refreshes once; a failed load is repeated once when the connection returns and not again
  while it stays.

- [ ] **M2-23 List state for a real store.** The sort of each tab is kept across restarts, in the preferences
  store, which `:data` gains here. The List's state is produced off the main thread, and a change to one entry
  rebuilds its row and its tab alone.
  **Needs:** M2-22.
  **Check:** tests: a new process over the same store shows the chosen sorts; after a "+1" every other row is
  the same object and one row recomposes; no state is built on the main thread.

- [ ] **M2-24 [L] Title page for a real store.** A title opened from a related row that is neither on the list
  nor stored shows, for the score, the episode count and the rank it does not know yet, what the hand-off
  proposes and the owner accepts, without a claim that MAL has none. The title and the synopsis can be selected
  and copied. The page and its sub-screens read the entries of the titles they show, not the list. Case:
  `opened-from-related`.
  **Needs:** M2-22.
  **Check:** the rubric on the case; tests: a page of twelve related titles is not rebuilt by a change to
  another entry; the title and the synopsis offer the selection.

- [ ] **M2-25 Posters from the network.** Images load through the app's HTTP client: a row asks for the picture
  of its size, the header for the large one with the row's picture in its place until it arrives. The loader has
  a test of its own. Debug builds keep the stand-in art for the demo, and no render asks the network.
  **Needs:** M2-09, M2-14.
  **Check:** tests against a local server: a row's request is for the medium picture and carries the app's
  `User-Agent`; the header shows the row's cached picture in its first frame; a failed image leaves the
  placeholder. A render of the List is byte-identical to one without a network.

- [ ] **M2-26 The real title page and its link.** The live graph's titles are M2-17. A
  `myanimelist.net/anime/{id}` link, with or without a slug or a trailing slash, opens the title page with the
  List beneath it (section 6.3); no other address is claimed. While the system does not let the app open these
  links, the app says so once in a screen of its own that leads to the system's setting for it; the row in Settings
  comes with M7.
  **Needs:** M2-17, M2-24.
  **Check:** a parser test with ten forms of the address; `tools/emu run` on the demo source: the link starts
  the app on the title page, and Back shows the List.

- [ ] **M2-27 [L] Watch order on the title page.** The page follows the chain of sequels, one request per title,
  each stored, and shows the watch order, so a film between seasons does not hide the next season; the order is
  decided in `:core`. Cases: `chain-loading`, `chain-of-four`, `chain-failed`.
  **Needs:** M2-26, and section 9.4 changed by its own procedure (section 9.1).
  **Check:** the rubric on the cases; tests: a chain is walked once and read from the store afterwards; a
  failure in the chain leaves the page as it was.

- [ ] **M2-28 Back gesture preview.** The back gesture shows the screen it leads to and a cancelled gesture
  restores the screen exactly (section 8.5).
  **Needs:** nothing.
  **Check:** the tests and the frames image section 8.5 asks for; the owner judges the gesture on his phone.

- [ ] **M2-29 Messages that are the only way back.** A waiting Undo or Retry is watched again when the list is
  read again after a failure, and is dropped when its title's values change, not only its time stamp.
  **Needs:** nothing.
  **Check:** `ShellMessagesTest`: both cases fail on the code of today.

- [ ] **M2-30 List fling cost.** The one long frame of a fling of a long tab back to the top is explained with
  measurements on the preview build, and what is the app's own is removed. The hand-off says, with numbers,
  whether a baseline profile is worth its module now or with the release.
  **Needs:** nothing.
  **Check:** a test that counts the work of the fling's frames, in the style of `ListFrameWorkTest`; frame times
  before and after from `tools/emu`, in the hand-off.

- [ ] **M2-31 Changed screenshots in CI.** A pull request's run lists the screenshots that differ from those of
  `main`'s last run, so a change to a shared component shows on every screen it moves.
  **Needs:** nothing.
  **Check:** a pull request that changes one string lists that screen's images and no other; one that changes
  none lists nothing.

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
  rejected for their numbering (A4) and how many are only estimated (A5, A6). The counts settle item 4 of
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
  notification and whether the re-check reached the network, which settles item 5 of section 13.
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

- Searches of one, two and three characters are recorded, to learn the shortest query MAL accepts (item 3 of
  section 13).
- Typing does not send a request per keystroke.
- Results that arrive page by page are held as a `StateFlow` of the pages loaded.

- [ ] **M5-01 [L] Search screen.** The stateless screen of section 9.6 on fixture states: before a query, results,
  the digits row, a browse list; no view model, no network.

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
(ARCHITECTURE section 13, item 6).

| Milestone | Goal | Features | Sources |
|---|---|---|---|
| P1 | Manga and a fuller list | Manga lists, title pages, search, rankings and statistics, behind an Anime / Manga switch; related manga on anime pages; rewatching, tags, comments and priority in the edit sheet; more list sorts and a filter across statuses; an offline write queue with a visible pending state; the cover opened full-screen, with the title's other pictures to swipe through; opening and ending themes; deep links to manga pages | MAL only |
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
