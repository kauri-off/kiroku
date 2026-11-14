# Kiroku architecture

Kiroku (記録, "record") is an unofficial Android client for MyAnimeList (MAL), written from scratch in Kotlin and
Jetpack Compose with Material 3, licensed GPL-3.0-or-later. It replaces the abandoned Flutter app dailyAL for its
owner. The four defects of dailyAL that it exists to fix are: too much noise on every page, poor layout, broken or
missing notifications, and home-made components. Layout quality is the first priority.

This document states what has been decided, what constrains the app from outside, and what must be true of the
result. How to build it is the implementing agent's decision; the code and its tests are the source of truth for
how things work.

- A **decision** is the owner's. An agent that needs to depart from one stops and reports.
- An **invariant** is a property of the result that a test must demonstrate. Invariants are labelled (A1, N3, …) so
  that tests and reviews can name them.

Sections: 1 Scope · 2 Data sources · 3 Sign-in with MAL · 4 Episodes aired · 5 Notifications · 6 Structure and
stack · 7 Persistence, errors and writes · 8 Design system · 9 Per-screen information budgets · 10 Localisation ·
11 Testing and screenshots · 12 Build, platform and distribution · 13 External facts not yet verified

## 1. Scope

**Version 1.0** is the minimum the owner uses every day, done well:

- Sign in with a MAL account; browse without an account.
- The user's anime list: read, change status, change watched episodes, score, dates, remove.
- The title page, including related titles and statistics.
- For titles that are still airing: episodes aired so far, total episodes, and how many aired episodes the user has
  yet to watch ("to watch").
- Reliable notifications when a new episode of a title on the user's list has aired.
- Search for anime, and four browse lists (this season, top airing, top rated, most popular).
- The user's profile and statistics; another user's list and statistics by user name.
- Russian and English user interface.

**After 1.0**, `ROADMAP.md` lists what is planned (manga, a deeper title page, a schedule, read-only forums) and
which dailyAL features are not built, each with its reason. Android only. Later features have to fit without
redesign: at most five top-level destinations (section 6.3); a new title-page section starts as a row in the page's
"More" group (section 9.4); a budget grows only by the procedure of section 9.1; a new data source is an optional
section that can fail without affecting its page (section 2.3).

## 2. Data sources

### 2.1 Decision

| Data | Source | Needs sign-in |
|---|---|---|
| Titles, search, rankings, seasons, related titles, status statistics, recommendations | MAL API v2 | No (client id only) |
| The signed-in user's list (read and write), own profile | MAL API v2 | Yes |
| Another user's public list | MAL API v2 | No (client id only) |
| Airing times of episodes | AniList GraphQL, keyed by MAL id | No |
| Score histogram, characters, videos, reviews, other users' avatar and "about" | Jikan v4; not in 1.0 | No |

- Kiroku has no server of its own, scrapes no HTML and loads no remote configuration.
- AniList is chosen for airing times because it is a documented public API that knows real episode times, breaks
  and delays, and covers long-running shows regardless of season (dailyAL issue #8). MAL's own `broadcast` field (a
  weekday and a Japan-time slot) knows none of that and only feeds the estimate of section 4.
- **Privacy.** AniList receives the MAL ids of the unfinished titles on the user's list and of an airing title whose
  page is open, and nothing else about the user. There is no analytics and no crash reporting.

### 2.2 External constraints and their consequences

MAL (from its API reference):

- The only user endpoint is `users/@me`, but anyone's public list can be read with the client id alone. Kiroku
  downloads another user's list and computes their statistics itself, with the same code as for the signed-in
  user. Their avatar, join date and "about" are not available and are not shown in 1.0.
- There is no user search: another user is opened by exact user name or by a `myanimelist.net/profile/…` link.
- Title statistics are the status distribution and the member count; MAL's API has no per-score histogram.
- Search has no filter parameters, so 1.0 has no search filters.
- Dates may be partial ("2017-10-23", "2017-10", "2017").
- Entries flagged as not safe for work are returned only on request. **The user's own list always requests them,
  so an entry never vanishes from the list** (dailyAL issues #11, #60). Search and browse leave them out unless the
  setting "Show adult content in search" is on.

AniList (from live queries):

- No authentication. A request without a `User-Agent` is refused. It reports a limit of 30 requests per minute;
  the app limits itself below that, and is conservative towards MAL, which publishes no limit.
- **Many titles that AniList marks as releasing have no schedule at all**, some MAL ids have no AniList entry, and
  AniList sometimes numbers episodes differently from MAL (a second season that continues at 13). All three are
  normal cases for section 4, not errors.

### 2.3 When a source fails

| Source | Failure | What the user gets |
|---|---|---|
| MAL | Network or server error | Cached data stays, with a one-line snackbar: "No connection" when the device is offline, otherwise "Could not refresh". With nothing cached: the error state with Retry. A write is rolled back (section 7.3). |
| MAL | Refused when reading another user's list | "This list is private". Not treated as rate limiting. |
| MAL | Refused anywhere else | Treated as rate limiting: background work backs off, and a read in the foreground says "MyAnimeList is refusing requests, try again later". A refused write is a failed write (section 7.3). |
| AniList | Any failure or rate limit | Stored airing data stays in use, so planned notifications still fire. A title with no stored data shows the estimate (section 4). |
| An optional source (after 1.0) | Any failure | Its section collapses to one line, "Unavailable · Retry", and never blocks or delays the rest of the page. |

The app holds the permission `ACCESS_NETWORK_STATE`, and "offline" means the device has no network that reaches the
internet.

## 3. Sign-in with MAL

### 3.1 External constraints

From MAL's authorization reference:

- The only grant is the authorization code grant with PKCE, and **only the `plain` PKCE method**: the code
  challenge equals the code verifier, 43 to 128 characters. Kiroku's client has no secret. This is the strongest
  flow MAL offers: an app that intercepts the redirect cannot redeem the code without the verifier.
- The reference contradicts itself on the access token's lifetime, so Kiroku trusts only the `expires_in` it
  receives. The refresh token lives one month. A refresh returns a new pair and revokes the old one.

| Value | Release build | Debug build |
|---|---|---|
| Application id | `io.github.kauri_off.kiroku` | `io.github.kauri_off.kiroku.debug` |
| OAuth redirect URI | `io.github.kauri-off.kiroku://oauth` | `io.github.kauri-off.kiroku.debug://oauth` |
| MAL client id | one registered client (app type "android", no secret) for both | same |

- The two redirect URIs are the ones registered with MAL. **A redirect URI is its own constant per build type,
  never derived from the application id**: an underscore is not legal in a URI scheme. The URIs stay lower case,
  because browsers lowercase schemes and Android matches them case-sensitively.
- A debug build installs next to the release build, with its own data, and is labelled "Kiroku debug".

### 3.2 What must hold

The session is **signed out**, **signed in** or **expired**, and every part of the app sees the same state.

- **S1.** Sign-in happens in the user's browser (a Custom Tab where available), never in a WebView.
- **S2.** A sign-in completes even when the app's process is killed while the browser is open.
- **S3.** A redirect is accepted only for a sign-in this app started a short time ago, only when its `state`
  matches, and only once. Anything else ends with "Sign-in failed, try again".
- **S4.** The app is never signed in without usable tokens: a token response counts only when it succeeded and
  carries both tokens (dailyAL issues #84, #91).
- **S5.** Requests that find the access token rejected at the same moment, background work among them, cause
  exactly one refresh. A rejected token leads to one refresh and one retry, never a loop.
- **S6.** A refresh that MAL rejects ends the session: it is **expired**, the tokens are deleted, the cached list
  stays readable, every screen shows the banner "Session expired · Sign in", and background work stops calling
  MAL. Any temporary failure, a success without tokens included, keeps the tokens and the session.
- **S7.** A user who only receives notifications and never opens the app keeps their session: the tokens are
  renewed in the background well inside the refresh token's month.
- **S8.** Tokens are stored encrypted with a key from the Android Keystore, written so that an interrupted write
  cannot lose the session, and excluded, like the settings, from cloud backup and device transfer. A token store
  that cannot be read means signed out, never a crash.
- **S9.** Signing out removes the tokens and everything stored about the user.

### 3.3 The client id

- In a public client the id can be read out of the APK, so it is not a secret, but it is **never committed**: every
  fork registers its own. Agents never read or print it.
- The build takes it from the Gradle property `kiroku.malClientId`, else from the environment variable
  `MAL_CLIENT_ID`; a blank value counts as absent. On the maintainer's machine it comes from
  `~/.gradle/gradle.properties`.
- A build without a client id succeeds. A **debug** build without one runs in demo mode (section 6.4); a
  **release** build without one shows the sign-in screen in its no-client-id form (section 9.2). Tests never need
  the id.

## 4. Episodes aired

dailyAL had three definitions of "aired". Kiroku has one:

> Episode *n* of a title **has aired** at instant *t* when its scheduled first broadcast time is at or before *t*.
> The **aired count** of a title at *t* is the highest episode number that has aired at *t*.

The broadcast time is AniList's, normally the first Japanese TV or streaming slot, not the time the episode
appears on a streaming service in the user's country; the notification delay setting exists for that. A count is
**exact** when it comes from an AniList schedule that agrees with MAL, **estimated** when it is computed from MAL's
start date and weekly broadcast slot, and otherwise **unknown**. "To watch" is the aired count minus the episodes
watched. Airing data is kept for the **tracked titles**: the unfinished titles on the user's list in Watching,
Planned or On hold. It is refreshed after a list sync, when the app is opened with stale data, and periodically in
the background.

- **A1. One definition.** The aired count, its confidence, the total, the next episode, "to watch" and the keys of
  the "Up next" order (section 9.3) are computed in one place in `:core`, from stored data and an injected clock.
  Every screen, sort order and notification uses it; the list row and the title page show the same numbers.
- **A2. Correct between refreshes.** The aired count rises when a stored air time passes, with no network call, and
  a visible screen updates at that moment without polling.
- **A3. Totals.** The total is MAL's episode count when MAL has one, otherwise AniList's, otherwise unknown. A
  title MAL marks as finished has aired completely. The aired count never exceeds a known total.
- **A4. A schedule numbered differently is not used.** When AniList's episode numbers evidently continue an
  earlier season (they run past MAL's total, or reach a number that a show with MAL's start date cannot have
  reached), AniList's data is not used for that title.
- **A5. Doubt is shown as an estimate.** A count is estimated, not exact, when the two sources disagree on the
  total by more than the one episode a recap explains, or when AniList appears to have stopped tracking the title.
- **A6. The weekly estimate is bounded.** It is used only when MAL gives a full start date and a broadcast weekday
  and time, and the result cannot run away (the total is known, or the show started recently). Without it the
  count is 0 for a title that has not started and unknown otherwise. A count of 0 is exact only when a stored
  AniList schedule puts episode 1 in the future; a 0 that comes from MAL's date or status is an estimate.
- **A7. Display.** An estimated count is prefixed with "~", and so is a "to watch" figure computed from it. An
  unknown count is not shown.
- **A8. "To watch" belongs to Watching.** It exists only for a title whose list status is Watching, that has not
  finished airing, and whose aired count is known, and it is never negative. A finished show the user is part-way
  through has ordinary progress.
- **A9. A refresh loses nothing still needed.** After a refresh, an episode that aired on time still has its air
  time for as long as a notification or the "Up next" order can need it; an episode AniList moved has its new
  time; one AniList removed is gone, even when its old time is in the past. A small difference between the
  device's clock and AniList's never makes an episode fall between "future" and "aired".
- **A10. A failed refresh changes nothing.** When any part of a title's data could not be fetched, that title's
  stored data stays exactly as it was.
- **A11. A newly tracked title does not wait.** A title that enters the tracked set (added, or moved back from
  Completed or Dropped) gets its airing data right away. If that fails, it shows the estimate until the next
  refresh.
- **A12. Outside the tracked set**, the title page of an airing or not-yet-aired title still shows exact airing
  data, fetched for that page. Such data never leads to a notification.
- **A13. A finale is not lost.** The last episode of a show can still be announced after MAL has marked the show
  as finished.

## 5. Notifications

### 5.1 Decisions

dailyAL scheduled weekly repeating alarms with a frozen text, never cancelled one, scheduled only when the app was
opened, considered only the current season, and checked nothing when an alarm fired. Users got notifications for
dropped, completed and unlisted titles, on wrong days, and none for long-running shows (issues #37, #86, #94, #101,
#104). Hence:

- **Alarms are one-shot and are re-checked when they fire.** What is due and what comes next is computed from the
  stored list and airing data every time; there is no stored plan that could drift. Only the next due notification
  is scheduled, not one alarm per title, which keeps the app far from the system's alarm quotas.
- **A late notification is acceptable; a missing one is not.** The alarm is inexact by default, which needs no
  permission, and exact when the user has granted exact alarms. The manifest declares `SCHEDULE_EXACT_ALARM`;
  `USE_EXACT_ALARM` is not used, because Google Play reserves it for alarm-clock and calendar apps.
- The app never opens a system settings screen unasked and never asks for a battery exemption on its own.
- What is **notifiable** is defined in `:core`: the title's list status is Watching (or Planned, if the user
  enabled that); its aired count is exact and the episode has a known air time; the user has not watched it; it
  has not been announced; and its **notification time** (air time plus the user's delay) is later than the moment
  the user started following the title, in the sense of N5.

### 5.2 What must hold

- **N1. Exactly once.** An episode that has aired is announced at most once, however many triggers run at the same
  time, and also when airing data is refreshed between its air time and its notification, the alarm is lost, a
  delay is configured, the device restarts or the app is updated.
- **N2. Not lost.** A notifiable episode whose notification time has passed is announced the next time the app
  gets to run: the alarm, a device restart, an app update, the app being opened, or periodic background work that
  needs no network. A notification that would come more than three days late is not shown.
- **N3. Re-checked.** When the alarm fires and a network is available, the air time is checked again within a few
  seconds: a postponed episode is rescheduled instead of announced. When the check fails or takes too long, the
  stored data is used.
- **N4. Never for anything else.** No notification for a title that is dropped, completed, on hold, removed or not
  on the list, for an episode the user has watched, for a planned title unless that is enabled, or from an
  estimated or unknown count. An episode whose air time is not known is never announced.
- **N5. Nothing from before.** Signing in, turning notifications on, turning on "Also for planned titles", adding a
  title or changing its status announces no episode whose notification time came before that moment. A status
  change that arrives through a sync counts like one made in the app. With a delay configured, an episode that
  aired before the change and whose notification time comes after it is still announced.
- **N6. One per title.** Several due episodes of one title give one notification, for the highest of them.
- **N7. Withdrawn when stale.** A posted notification disappears when its title stops being notifiable or the user
  has watched the announced episode.
- **N8. The alarm follows the data.** After any change to the list, the airing data or a notification setting (the
  delay alone included), the scheduled alarm is the earliest coming notification time, or none. It is restored
  after a device restart, an app update, a change of the exact-alarm permission, and when the app is opened after
  a force-stop. A change of time zone moves nothing.
- **N9. The action counts once.** "Mark watched" marks the next unwatched episode and never jumps the count over
  episodes the user has not seen. A second tap, a retry after the server already accepted it, or a sync that
  already brought the change do not raise the count again. Without a network it waits and retries; if it finally
  fails, the notification is replaced by "Could not save · Open". It works on every supported Android version: on
  Android 8 to 11 the system runs such work as a foreground service, which shows "Saving…" on its own
  low-importance channel "Saving changes"; on Android 12 and later no foreground service starts or is declared.
- **N10. The record of announced episodes survives** app updates and database changes (section 7.1).

Tests demonstrate each of these without a device, with every dailyAL failure case among them. Before a release,
one emulator run shows a notification posted in Doze and the alarm restored after a restart.

### 5.3 What the user gets

- **Text.** Title: the anime title in the preferred title language. Text: "Episode 16 aired", and when more than
  one aired episode is unwatched, "Episode 16 aired · 3 to watch", in the language in effect when it is posted.
  Tapping opens the title page with the List beneath it.
- **Grouping.** All episode notifications share one group with a summary ("3 new episodes"), so an evening with
  several shows is one stack. **Channel:** "New episodes", default importance.
- **Action.** One action, labelled with the episode it marks: "Mark ep. 14 watched".
- **First run.** Notifications are on by default. On Android 13 and later the system permission prompt appears
  once, on the List, right after the first successful sign-in, with no custom dialog before it. If the user
  denies, the setting shows "Blocked in system settings" with a button, and nothing else in the app mentions it.
  Planning continues either way, so a later grant needs no further step.
- **Delay.** A setting "Notify": "when the episode airs in Japan" (the default), "30 minutes later", "1 hour
  later", "3 hours later".
- **Limits the app cannot remove.** The system may defer the alarms and background work of an app the user rarely
  opens, and a force-stopped app receives nothing until it is opened. The notification settings say so and show
  whether exact alarms are granted and whether the app is battery-restricted, each with a link to the system
  screen. Their status panel (section 9.8) shows which link broke when a notification is missing; it is what the
  owner checks during acceptance.

## 6. Structure and stack

### 6.1 Modules

| Module | Contains | Why separate |
|---|---|---|
| `:core` (Kotlin/JVM, no Android) | Domain types, the airing model, what is notifiable, the statistics calculator, the rules of list writes and syncs over a store and MAL, an in-memory store of the list, repository interfaces, typed errors; in its test fixtures, the fixture set and demo mode's repositories over it | Its tests are plain JVM tests that run in seconds. Rules that must have one definition live where no screen can re-implement them. |
| `:data` (Android library) | The MAL and AniList clients (the list's under `:core`'s list engine), sign-in and token storage, the database and the list's store, the other repository implementations | Network and database types stay inside it, so the user interface depends only on `:core` types. |
| `:app` (Android application) | Theme and components, screens and view models, navigation, notifications and background work, wiring | The only module that knows Compose. |

- Dependencies point one way: `app → data → core` and `app → core`. The base package is
  `io.github.kauri_off.kiroku`. Failures cross a module boundary as typed errors, not exceptions, with one
  exception: a flow that cannot go on ends with a `DataException` carrying the typed error, so every collector
  of a repository flow handles it.
- The fixtures and demo mode serve `:core` tests, previews, screenshot tests, view-model tests and debug builds.
  Release code cannot see them, and a release build that would contain them fails.
- One module per feature was rejected: with about ten screens it adds build configuration and no protection that
  three modules do not already give.

### 6.2 State

- A screen is a stateless composable, `XScreen(state, onEvent)`, drawn entirely from one immutable state value. It
  never touches a view model, a repository or navigation. This is what previews and screenshot tests render.
- A view model takes the repositories and services it uses, never the whole graph, and of the list only a view that
  cannot write. It produces the state from the repositories and the user's input only while a screen shows it, a
  rotation included, and reads, syncs or refreshes nothing before then.
- A page opened from a row or another page shows what that row or page showed from its first frame, and keeps
  showing it until the store knows more, also after a failed refresh. A page opened with nothing known starts in its
  loading state.
- Every screen that asks for data gives a way out of a failure. With nothing to show: the error state with the cause
  and Retry in place of the content, never a message alone. With older content shown: it stays, the failure is a
  message, and the screen can be refreshed by pulling. A failed action of the person: a message with Retry that is
  not lost (section 7.2). A secondary part (a poster, a strip): a placeholder, loaded with the screen's next refresh.
- Text a person types is state the view model owns and changes inside the event; no flow sits between a keystroke and
  its field.
- A write to the list runs in the shell's scope, never a view model's, so the write and its message outlive the
  screen.
- A thin `XRoute` connects the view model to the screen and to navigation, and is the only place that knows either.
- There is no MVI framework and no event bus.
- Snackbars share one state, owned by the shell and handed to every screen as a parameter; the screen on top
  draws it, so a message that outlives its screen still shows. The session banner (S6) is part of each
  signed-in screen's own state.

### 6.3 Navigation

- Top-level destinations in 1.0: **List**, **Search**, **Profile**, in a bottom bar on phones and a rail on wide
  windows. Settings opens from Profile.
- Reserved for later, in this order: List, **Schedule**, Search, **Community**, Profile. Five is the maximum;
  anything else becomes a row inside Search.
- Each destination keeps its own back stack. Tapping the current destination again pops it to its root.
- Back from the root of Search or Profile goes to List; Back from the root of List leaves the app.
- A notification or a deep link builds the stack List → Title, so Back never leaves the app unexpectedly.
- The app opens on List, tab Watching. Signed out, List shows its empty state with one "Sign in" button; "Browse
  without an account" on the sign-in screen goes to Search.
- Predictive back is on.
- Deep links: `https://myanimelist.net/anime/{id}` (with or without a slug or trailing slash),
  `https://myanimelist.net/profile/{name}`, and the OAuth redirect.

### 6.4 Stack, wiring and demo mode

`gradle/libs.versions.toml` lists what is used and is the only place a version is written. What it cannot say is what
was chosen against, and why:

- **Navigation:** Jetpack Navigation 3. Navigation Compose is more machinery than this app needs.
- **Network:** OkHttp, Retrofit and kotlinx.serialization, with AniList queries sent as plain requests. Not Ktor (a
  second HTTP abstraction) and not Apollo (code generation and a plugin for a few queries).
- **Storage:** Room, with its annotation processor only in `:data`; DataStore Preferences for settings.
- **Sign-in:** Custom Tabs and the Keystore directly (`security-crypto` is deprecated).
- **Wiring** is done by hand. Not Hilt: a plugin and an annotation processor in every module for a graph of roughly
  fifteen objects.
- **Tests and checks:** JUnit 4, Robolectric, Roborazzi (Paparazzi's current line is alpha), ktlint, Android Lint
  (detekt's current line is alpha).
- **Not used at all:** a chart library, AppCompat, `kotlinx-datetime`, a paging library, Google Play Services,
  Firebase, any closed-source dependency (which keeps F-Droid possible).
- **Demo mode:** a debug build can run entirely on the fixtures, held in memory, writes included, and a debug build
  without a client id starts in it. Demo data follows the device's date, so every weekday and weekly slot in it stays
  what it claims, and every "today" does on a device between UTC−9 and UTC+8. A new process starts again from the
  fixtures. Debug builds and the preview build (the release build's code on the demo) have a debug menu that makes
  the demo fail and resets it. A release build holds none of it.
- Every request names Kiroku and its version in its `User-Agent`. Tokens and the client id never appear in logs. A
  write is never retried automatically, except the notification action (N9).
- From dailyAL come ideas and endpoint knowledge only. No code is ported and no asset is copied.

## 7. Persistence, errors and writes

### 7.1 Storage

- **The database is the single source of truth.** Screens read from it, and network results are written to it. It
  holds everything shown from cache: titles, the user's list, related titles, airing data, the record of announced
  episodes, the user's profile. Cached data is refreshed when it is shown stale, on pull-to-refresh, and by
  periodic background work.
- Held in memory only: search results, browse lists, other users' lists and statistics (kept for the session), and
  the airing data of A12.
- Every schema change is a real migration with a migration test. **There is no destructive fallback**: it would
  erase the record of announced episodes and announce them again (N10).

### 7.2 Errors and offline behaviour

- A screen has four states: content, loading (no content yet), empty, and error (no content and the load failed).
  **With cached content, a failed refresh is a snackbar, never a replaced screen.**
- Offline, the list, visited title pages, the airing counts and the statistics work from the database. Search shows
  the error state with "No connection" and Retry. A title page whose details were never loaded shows that error
  state where they would be (section 9.4) and, as that may be below the first screen, a snackbar.
- All messages are snackbars or inline text. Toasts are not used. A snackbar outlives the screen that caused it, and
  one is shown at a time. One that is the only way back (a completion's Undo, a failed save's Retry) is never
  replaced, another of its kind waits behind it while its title's values are unchanged, and its time runs only
  while the user can see and tap it; a finger holding any message stops its time, which starts again in full when
  let go. Any message, one that must stay too, can be swiped away; that ends it as its time running out does and
  runs no action. Ordinary messages are not queued.

### 7.3 Writes to the list

Writes are optimistic: an edit is visible at once and sent in the background. A write from a screen returns once it
is applied locally, and its failure reaches the user also after the screen that made the edit is gone. The
notification action instead waits for the server's answer (N9). "+1", the stepper and "Mark watched" are steps,
applied to the entry's values as they are when sent, not to values read earlier.

- **W1. In order.** Quick successive edits of one entry never overtake each other, and the server ends with the
  last value shown.
- **W2. A failure returns to the server's state.** When a write fails, the entry shows what the server last
  confirmed (not "the value before the tap"), no later edit of that entry that was still waiting is sent, and a
  snackbar says "Could not save · Retry", also when MAL refused the write; with the session expired it offers no
  Retry, as the banner offers sign-in (S6). Retry applies, as a new edit, the values shown just before the failure.
  Example: the server has 12; two taps show 13, then 14; the request carrying 13 fails. 14 is not sent, the row
  shows 12, and Retry sends 14.
- **W3. Undo is one write** that takes back the undone edit alone, applied to the values as they are: a step is
  undone by the opposite step, and any other value the edit set (status, dates, a typed count) goes back only
  while it still holds what the edit put there.
- **W4. A sync never reverts an edit made while it was running**, including an edit whose request completed
  before the sync's download did. A title added during a sync is not deleted by it, and one removed during a sync
  does not come back. Removals made on the website are still reflected for every other entry.
- **W5. Nothing stays unsynced after a process death.** An entry whose write died with the process returns to the
  last confirmed state at the next start, and the list is then synced whatever its age. No entry is left out of
  syncs for good.
- **W6.** One list sync runs at a time.
- **W7. Automatic status changes match MAL's website.** When the watched count reaches MAL's total, or is set above
  it, the status becomes Completed and an empty finish date becomes today, in the same write. The first episode of a
  planned title sets Watching and an empty start date. Nothing else changes on its own: no date the user has is
  overwritten, and a Completed title stepped below its total stays Completed.
- **W8.** There is no offline write queue in 1.0, except the notification action (N9).
- **W9. The watched count has no ceiling.** MAL's API sets none, and a user must be able to record what they
  watched: every write ("+1", "Mark watched", the stepper, the edit sheet) accepts any count of zero or more, above
  the aired count and the total included. "To watch" is then zero (A8).

## 8. Design system

The rule behind every choice: stock Material 3 Expressive components with default shapes and behaviour, composed,
never restyled into something else. Expressive means are spent where they show the user a state or answer an action;
everything else stays calm, because this is a tool opened many times a day.

### 8.1 Theme and window

- The Material 3 Expressive theme with its default typography, shapes and motion scheme. Only the colours are
  Kiroku's.
- Material 3 is pinned ahead of the Compose BOM while the version with Expressive is a release candidate, and its
  experimental API is opted into in one file, so that what may break on an update is in one place.
- **Dynamic colour** is on by default on Android 12 and later, with a setting to turn it off. Below Android 12 and
  in screenshot tests the app uses a fixed light and dark scheme from one seed colour, a teal, deliberately not MAL's
  blue. One screenshot variant uses a second, contrasting seed to expose misused colour roles.
- Light and dark follow the system; a setting overrides (System, Light, Dark).
- Colours are used only through the theme's colour roles. Emphasis uses `primary`; "to watch" uses `tertiary`;
  errors use `error`.
- **Edge-to-edge.** Content is never hidden under a system bar: the last row of every list can be scrolled clear of
  the gesture bar, and a snackbar never sits under it.
- **Launcher icon:** original and abstract; no characters, no MAL name or logo.

### 8.2 Typography

The default Material 3 type scale with the system font. Screens use only these roles:

- `headlineSmallEmphasized`: the title on the title page. `titleMedium`: section headers and the title in the edit
  sheet.
- `titleLargeEmphasized`: the number in `StatNumber` and the user's own score on the title page.
- Emphasised roles mark only the subject of the title page and the few numbers a screen is opened for.
- `bodyMedium`: body text and supporting lines. `labelLarge` and `labelMedium`: captions.
- Top app bar titles, row headlines, buttons and chips keep the role their component sets.

### 8.3 Spacing and shape

Layout code uses only the `Spacing` tokens, the theme's named sizes and component defaults: no literal dp value or
colour outside the theme.

- Horizontal screen padding: `l`. Between sections: `xl`. Between related elements inside a section: `s`.
- Content has a maximum width and is centred on wider windows.
- Every tappable element is at least 48 × 48 dp.
- Posters are cropped to one of three named sizes: the row poster in every `AnimeRow`, the strip poster and the
  header poster.
- **A row poster leaves its text room at large text:** it narrows as the font scale grows, so a title beside it keeps
  a readable line length.
- **A caption of a strip card is never broken inside a word**, at any font scale. This is a rule of the component,
  and a screen never passes a width.
- **Where a title must break, it breaks after a hyphen rather than inside a hyphenated segment**, wherever it is
  shown, unless the segment alone is wider than the line.
- **Large text** means a font scale of 1.5 or more. With it the three numbers of the Profile summary are stacked one
  per line (section 9.7). Apart from that and the two poster rules above, nothing depends on the font scale.

### 8.4 Components

The complete list. Adding one needs a reason in the pull request, an entry here and a gallery screenshot.

| Component | Built from | Purpose |
|---|---|---|
| `Poster` | An image | A 2:3 image with a placeholder in a surface colour role; no text over it, ever |
| `AnimeRow` | `ListItem` | Poster, headline, up to two supporting lines, one optional trailing element, an optional long press with haptic feedback that screen readers are offered too. "+1" keeps one place and one size on every row, prominent while the row has episodes to watch and quiet once the user has caught up |
| `PosterCard` | `Poster` and text | For horizontal strips: a poster, a title (line cap two), caption 1 (line cap two) and caption 2 (line cap one). All cards of a strip have one height, whatever their text |
| `SectionHeader` | Text and `TextButton` | Section title with an optional "See all" |
| `StatBar` | Text and `LinearProgressIndicator` | One labelled bar of a distribution, static. A screen reader hears it as one sentence |
| `StatNumber` | Two texts | A number with a caption (the MAL score; the Profile summary) |
| `FactRow` | `ListItem` | A label and a value |
| `EpisodeStepper` | `FilledTonalIconButton`, `OutlinedTextField`, `FilledTonalIconButton` | Minus, a numeric field with "/ 24", plus. Typing a number is how a user catches up many episodes. The count has no ceiling (W9) |
| `ScreenState` | `LoadingIndicator`, text, `Button` | The loading, empty and error states, identical everywhere |
| `SessionBanner` | `Surface`, text, `TextButton` | "Session expired · Sign in" |
| `ConnectedChoice` | `ToggleButton` | One of a few options, all visible, as a connected button group in as few rows as fit |
| `ListTabs` | `Tab`, `TabRowDefaults.PrimaryIndicator`, `HorizontalDivider` | The List's tab row, which follows the pager between two tabs, as the stock row cannot |

Stock Material 3 components are used directly for everything else. Icons are Material icons. Where Material offers
several components for one job, these are the choices:

| Job | Choice | Why |
|---|---|---|
| A wait of unknown length for a whole screen, and pull-to-refresh | The loading indicator; for a whole screen, once the wait has lasted as long as the title page's (section 9.4) | One sign for "this screen is being fetched" |
| A wait for part of a screen already shown, and progress of counted work | The wavy linear progress indicator | The wave says that work is running, and stops when it does |
| A share of a whole that does not change | The flat linear progress indicator | Nothing is running, so nothing waves |
| One of a few options, all visible | A connected button group of toggle buttons, in as few rows as fit | The chosen one differs in colour and in shape, not in colour alone, and choosing one moves no other |
| A button with a container, and an icon button | With its stock press shapes | The press is answered where the finger is |
| The action a page is opened for (the title page's list block) | The medium button size | It is the one large target on the page |
| A row that opens something | The list item that takes the click itself | Its own press shape, focus ring and semantics |
| One of a list of options with a radio button | A list item that looks the same chosen or not | The radio button states the choice once |
| The navigation bar on phones | The short navigation bar | More room for content |
| Top app bars | The small top app bar | A large or flexible bar spends the first screen on a title the user knows |
| Lists of content | Plain list items, not segmented ones | A container around every row is a card used as decoration |

At a start the system's splash stays until the List has content, for no longer than the title page's wait.

Expressive means that only decorate are not used: wavy sliders, images clipped to or morphing between shapes, split
buttons, floating toolbars, the FAB menu, button groups that resize under the finger, vibrant colour variants.

Not allowed anywhere: custom-drawn buttons or pills, cards used as decoration, gradients, shadows beyond component
defaults, shimmer placeholders, text or badges drawn over images, chips that do nothing when tapped, auto-scrolling
carousels, more than one floating action button (1.0 uses none), a tab row together with another navigation level
for the same content, second-by-second countdowns.

### 8.5 Motion

Motion is functional and sparse. Stock components keep their own motion, which in the Expressive theme includes a
shape that changes while a control is pressed or chosen and springs that overshoot slightly. Anything beyond that
needs a purpose stated in the pull request: feedback, showing where something came from or went, making a state
change legible, or preventing a jump. Decoration is not a purpose.

- **Not animated by the app:** row taps, "+1", typing in a filter, a data refresh, re-entering a screen, numbers
  counting up, anything looping except a progress or loading indicator while work runs. No parallax, no full-screen
  zoom or rotation, no bounce, no staggered lists, no press effect of the app's own (the component's ripple and
  shape are the feedback).
- **Pages that follow the finger** (the List's tabs) are direct manipulation, not decoration. Released, a page
  settles; a tab chosen by a tap slides its page in the same way; the tab row follows the page.
- **One source.** Every animation the app writes takes its spec from the theme's motion scheme, and only from its
  effects springs, because the spatial ones overshoot: fast for a change inside a component and for an exit, default
  for a component's entrance, slow for a screen. One value is the owner's exception: the List's pages settle and
  slide with ViewPager2's snap timing, so that the gesture feels as in Droid-ify.
- **Patterns.** List to title page: the Material forward and backward transition. Destinations of the navigation
  bar: fade through. Sheets, menus and snackbars enter from their edge and leave the way they came, or the way they
  were swiped. Lists have stable keys and use the list's item animation for add, remove and move, including a title
  that joins or leaves a List tab between orderings (section 9.3), even when a data refresh brings the change.
  Content that finishes loading fades in.
  One thing moves per change; a container that enters brings its content with it.
- **Time.** An exit is shorter than the matching entrance, and an animation settles within the bound the motion
  tests set for its spring.
- **Behaviour.** State changes at once and the animation only smooths it. Every animation can be interrupted and
  reversed mid-flight, ends in the state of the last input, and never blocks a tap. Predictive back shows where
  Back leads, and a cancelled gesture restores the previous state exactly. With the system's animations turned off,
  every screen is complete and correct immediately. An idle screen has no animation running.

A task that adds or changes an animation shows with tests that it settles within its time, that re-triggering it
mid-flight ends in the right state, that the interaction is correct with animations off and that the idle screen has
nothing pending. It also renders the transition as a frames image next to the screen's screenshots (section 11.2), and
the hand-over names the transitions that changed. A state change that a stock component answers with motion of its
own gets the frames image, without the tests. How a transition feels on a device, and predictive back under a finger,
are judged by the owner from the installed app.

## 9. Per-screen information budgets

### 9.1 How a budget works

A budget is the owner's decision about what a screen shows before the user taps anything. It has three parts, and the
layout rubric reviews against all three:

- **Must show:** elements that have to be present whenever they apply. Leaving one out fails the screen.
- **Limits:** the most the screen may show, and what it never shows. Exceeding one fails the screen.
- **Behind a tap:** where everything else lives.

A budget names elements and rules, not wording or arrangement. The strings are in the resources and their recurring
forms in `docs/GLOSSARY.md`; which case shows what is stated by the screen's tests and screenshot cases. The terms
**logical line**, **line cap**, **ellipsis**, **base variants** and **content from MAL** are defined in
`docs/LAYOUT_RUBRIC.md` section 2 and are used here in that sense. **A string that does not fit is budget feedback for
the owner, never something the author solves with an ellipsis.**

The budget of a screen that is not built yet is a first statement of intent. The owner's look at the built screen
decides, and the budget then says what was decided.

**Extending a budget** (for features after 1.0) is a pull request that changes this section first: it adds the element
to Must show or to the limits of one screen, says what it displaces or why the screen can afford it, and is approved
by the owner before the layout task starts. New content defaults to "behind a tap". The list row never gains a line.

### 9.2 Sign-in

Must show: Back; the app's mark and name; one sentence; a "Sign in with MyAnimeList" button; a "Browse without an
account" text button; the line "Unofficial. Not affiliated with MyAnimeList." Limit: nothing else. The sentence
gives way to the state: "This build has no MyAnimeList client id" (section 3.3) and "Signing in…", both with the
sign-in button disabled, and "Sign-in failed, try again".

### 9.3 List (the start screen)

- Top app bar: the title "My list", a filter-by-text icon, a sort icon.
- A scrollable tab row: Watching, Planned, Completed, On hold, Dropped. No counts. The tabs' pages can also be
  switched by swiping the list sideways; each tab keeps its own scroll position.
- Rows (`AnimeRow`), at most three logical lines and one action.

**Must show per row:** the title (line cap two, then an ellipsis); line 2; line 3 when it applies; in the Watching
tab, one "+1" button.

- **Line 2** is always words of one shape: the user's progress ("Watched 12 of 24"; the total is left out when
  unknown, and the count is written as it is, also above the total, W9). For a title that has not aired yet it is the
  premiere, and in the Completed tab the user's score.
- **Line 3** exists only for an airing title: "to watch", in the `tertiary` colour, when there are episodes to watch;
  otherwise the next episode, when its time is known exactly.
- A row never shows the aired count: "to watch" carries it, and the title page shows it.

**Limits:** not in a row: MAL score, member count, genres, media type, studio, tags, a countdown, an edit button, a
progress bar.

**Default order.** Watching is sorted by default as "Up next": titles with episodes to watch first, the most recently
aired on top; then titles with a known time for the next episode or the premiere, the soonest first; then the rest, by
last update. "Up next" groups a row only by what the row shows, so the order never contradicts the rows. The other
tabs default to "Last updated". The sort chosen for each tab is remembered.

**Rows never move under the user's finger.** The order of a tab is computed when the app starts, when the tab's sort
is changed, and on pull-to-refresh, and only then does the tab's page start from the top. An edit, a sync, an air time
passing, a tab switch, the filter and coming back from a title page keep the order and the scroll position, and update
rows in place. Two things do change the visible rows: a row whose title no longer belongs to the tab leaves at once,
and Undo puts it back in the same place; a title that joins the tab appears at the end until the order is next
computed.

**Behind a tap:** the row opens the title page. A long press on a row, in every tab of the user's own list, opens the
entry's edit sheet. Filter replaces the top app bar title with a text field that filters every tab by title; Back or
clearing the field ends it. Sort opens a bottom sheet with at most five options. "+1" answers with a snackbar that
offers Undo, and works at and above the total (W9). When it completes the title (W7), the row leaves Watching and the
snackbar says where it went; Undo puts back the count, the status and the dates. After a row leaves its tab, a "+1"
on a row that moved up is not applied until the rows have stood still a moment, so a tap meant for the row that left
lands on no other.

**Read-only variant** (another user's list): the top app bar title is "<name>'s list" with Back; line 2 is their
progress or score; there is no line 3 and no "+1", because no airing data is fetched for someone else's titles.

### 9.4 Title page

One scrolling column in this fixed order. No tabs, no floating buttons. **Each fact is stated once on the page**: the
episode total, the year, the premiere and a related title each appear in one place only.

1. **Top app bar:** Back, and an overflow menu with "Open on MyAnimeList" and "Share". When the session has expired
   (S6), the `SessionBanner` under it, in every state.
2. **Header.** Must show: the poster; the full title with no line cap; the facts line; the MAL score as a `StatNumber`
   ("—" when there is none); the rank as its own short line, when MAL gives one. Limits: the facts line holds at most
   three facts (type, year, episode count), and leaves one out when another element of the page states it or when it
   says nothing, as an episode count of 1 does. The header holds nothing besides these five elements.
3. **List block.** Not on the list: one filled button "Add to list" across the content width. It adds the title as
   Planned, and its snackbar offers "Change", which opens the edit sheet. On the list, must show, as one block: the
   status as a tonal button in the same place, which opens the edit sheet; under it, side by side and each under its
   own label, the `EpisodeStepper`, whose changes are written at once, and the user's score or a "Rate" button (both
   open the edit sheet). The stepper has no ceiling (W9). A change that completes the title (W7) shows a snackbar with
   Undo, as on the List; other changes show none.
4. **Airing block** (must show for titles that are airing or not yet aired; absent otherwise). Limit: two logical
   lines. The first is the aired count, with "to watch" beside it, in the `tertiary` colour, when there are episodes
   to watch; the second is the next episode, when its time is known exactly. A title that has not aired yet shows its
   premiere alone. The list row and this block show the same numbers for the same title (A1), also for a title outside
   the tracked set (A12).
5. **Sequel** (must show when the title has a direct sequel; absent otherwise): a `SectionHeader` over one `AnimeRow`
   that opens it, with the user's list status for it when it is on their list. Which sequel is decided in `:core`: of
   several, the one that starts first. It is not repeated in the Related strip. Following the chain of sequels across
   titles is not on the page in 1.0.
6. **Synopsis:** a `SectionHeader` over the text, line cap four, which expands and collapses in place. Under it one
   line of plain text with the genres, line cap one.
7. **Related** (must show when the title has related anime other than the sequel): a `SectionHeader` over a horizontal
   strip of at most ten `PosterCard`s. A card shows the title, the relation, and the user's list status for that title
   or else its type and year. Order: prequel, sequel, parent story, full story, side story, spin-off, alternative
   setting, alternative version, summary; within a type by start date. "See all" is shown whenever a related title is
   not in the strip; the types "other" and "character" appear only there. Related manga are not shown in 1.0.
8. **Statistics** (must show): five `StatBar`s (Watching, Completed, On hold, Dropped, Planned) with counts and
   percentages, and one line with the member count.
9. **More:** rows that open sub-screens: "Details", and "Recommendations" when MAL gives any.

**Above the fold:** on a 411 × 891 dp screen the header, the list block and the airing block are visible without
scrolling, for a title of up to two lines.

**Loading.** Opened from a list or search row, the page already has the summary: header, list block and airing block
render at once, and one wavy linear progress indicator under them stands in for items 5 to 9 once the wait has lasted
a second, until the details arrive.
Only a page opened with no data at all (a deep link, or a page restored after the process was killed) uses the
full-screen loading state.

**Failed.** Details that could not be loaded leave the page as it is and put the error state with Retry where items 5
to 9 would be (section 7.2). After Retry, the indicator stands there at once.

**Not on the page in 1.0:** reviews, news, forum topics, videos, pictures, characters.

**Adding a section later.** A new section starts as a row in "More" and opens a sub-screen. It is promoted onto the
page, between Statistics and More, only through the procedure of section 9.1, with its own Must show and limit. The
order of items 2 to 8 does not change, and the above-the-fold rule stays.

### 9.5 Edit sheet

A modal bottom sheet. Must show: the anime's title (line cap one), so a sheet opened from a list row says what it
edits; the status as a connected group of five single-choice toggle buttons, in as few rows as fit; an
`EpisodeStepper`; a score slider 0 to 10 with the score names; start and finish dates; a text button "Remove from
list" (asks for confirmation); "Save", on screen without scrolling whenever the keyboard is closed. Limit: nothing
else. Nothing is written before "Save", and "Save" writes exactly what the sheet shows, an automatic status change
(W7) included. The count has no ceiling (W9). Dismissing the sheet with unsaved changes, by swipe or Back, asks
"Discard changes?". Rewatching, tags, comments and priority come after 1.0.

### 9.6 Search

- A Material search bar at the top.
- Before a query, must show: up to five recent searches, then four rows that open browse lists: "This season", "Top
  airing", "Top rated", "Most popular". Limit: nothing else; there is no home feed. Later browse entries are added
  as rows here.
- Results (`AnimeRow`), must show: the title (line cap two); the facts line of section 9.4, "TV · 2024 ·
  24 episodes"; when the title is on the user's list, a second supporting line, the list status line:
  "Watching · 12 / 24", without the count for Planned with none watched and for Completed at the total (dailyAL
  issue #67); trailing, the MAL score.
- A query that is only digits shows one extra row above the results, "Open title #<id>", which opens the title page
  of that MAL id. Limit: nothing else is added to the results.
- A browse list uses the same row, with the rank in front of the title for ranked lists.
- No filters in 1.0 (section 2.2).

### 9.7 Profile

**Own profile.** Top app bar: "Profile", a settings icon. Must show: the header (avatar, user name, "Joined 2019");
three `StatNumber`s ("Completed", "Days watched", "Mean score"); the status breakdown as five `StatBar`s; rows that
open sub-screens: "Score distribution", "Genres", "Formats and years"; a row "Open another profile", which asks for a
user name and lists the last five opened. Limit: nothing else.

The three numbers sit in one row; with large text (section 8.3) they are stacked one per line.

**Another user.** There is no header, because MAL's API gives neither avatar nor join date for other users; their
name is the top app bar title, with Back. Must show: the three numbers, the status breakdown, the three sub-screen
rows, and "View list" (the read-only variant of section 9.3). A long list loads with a progress line ("Loading
3 000 of 12 400").

Charts are horizontal bars only.

### 9.8 Settings

A plain list in groups.

**Must show:**

- **General:** Language, Theme, Dynamic colour, Title language (Romaji, English, Japanese), Show adult content in
  search.
- **Notifications:** one row that opens the notification settings sub-screen. That sub-screen must show: the
  switch; "Also for planned titles"; "Notify" (the delay); the status panel (notification permission, exact alarms,
  battery restriction, the time of the last successful sync, **Upcoming**: the next notifications with title,
  episode and time, and **Recently sent**: the last ones with the time posted); "Send test notification", which
  posts one through the real channel.
- **Account:** the signed-in name, Sign out.
- **About:** one row that opens the About screen, which must show: version, licence, source code, third-party
  notices, "Not affiliated with MyAnimeList or AniList", a link to the issue tracker.

**Limits:** each setting is one row with a title and at most one supporting text: its current value (one line) or a
one-sentence explanation (no line cap); no setting outside these groups; Upcoming shows at most five entries and
Recently sent at most twenty. Later settings join an existing group or add a group, through the procedure of
section 9.1.

### 9.9 Sub-screens

Each is a top app bar with Back and one list, with the `SessionBanner` under the bar while the session is expired
(S6), in every state. Limit for all: nothing but the list and its group headers.

| Sub-screen | Must show | Row limits |
|---|---|---|
| Related: see all | Every related anime, grouped under `SectionHeader`s by relation type in the order of section 9.4, then "Other" and "Character" | `AnimeRow` as in search results (section 9.6), without the trailing element |
| Details | `FactRow`s: alternative titles, dates, source, studios, rating, episode length, and the broadcast time while the title has not finished airing. Alternative titles are every title but the one shown as the main title, one row per kind, so no title is missing from the app. A fact MAL does not give is left out | One label and one value per row; a value has line cap three |
| Recommendations | The titles MAL recommends | `AnimeRow` exactly as in search results, including the trailing MAL score |
| Score distribution, Genres, Formats and years | `StatBar`s computed from the list; "Formats and years" has two groups | One `StatBar` per row; scores and years in their own order, everything else the largest first; at most twenty bars per group |

## 10. Localisation

- English strings are the default resources, Russian the `ru` ones. Both are written by hand. `docs/GLOSSARY.md`
  fixes the Russian term for every recurring concept and is required reading for any task that touches strings.
  The owner reviews the Russian text at the end of each milestone.
- **No user-visible string exists outside the `:app` resources**, and strings are split into one file per feature,
  so parallel tasks do not edit the same file. Strings of the debug source set live in that source set, in English
  and Russian.
- A line never breaks between a number and its word, unit or slash: `24 episodes`, `12 / 24`, `К просмотру: 2`.
- **Values from MAL that the user reads as interface text are translated by the app:** list status, airing status,
  media type, season ("Fall 2024" / "Осень 2024"), source, age rating, relation type, and genres. An unknown value
  falls back to MAL's code written as words ("light_novel" as "Light novel"), and an unknown genre to its MAL name.
  This fallback is the only interface text not from the resources. Titles, alternative titles, synopses,
  user names and proper names (studios, producers) are content and are not translated.
- Quantities use plural resources (Russian needs `one`, `few`, `many`, `other`). Sentences are never concatenated.
- Dates and times follow the locale and the device's 12/24-hour setting, in the device's time zone.
- **An air time** is written relative to today and never has both a weekday and a date; a next episode and a
  premiere each have one form on every screen. **A date from MAL** is written by its precision. `docs/GLOSSARY.md`
  has the forms in both languages.
- Language choice: on Android 13 and later the "Language" setting opens the system's per-app language screen. On
  Android 8 to 12 the app follows the system language; an in-app picker would require AppCompat.
- "Title language" chooses among romaji, English and Japanese; when the chosen one is missing, the romaji title is
  shown. Russian titles come after 1.0.

## 11. Testing and screenshots

### 11.1 Tests and recorded responses

- Every invariant of this document has a test that would fail if it were broken. Domain rules are plain JVM tests
  in `:core`; everything else is tested without a device as far as possible, and the whole app runs on an emulator
  once per milestone.
- **Tests of MAL mappers run against responses recorded from the real API, never against shapes written from
  memory.** An agent can neither read the client id nor sign in, so responses that need only the client id are
  recorded by a build task that uses the id without printing it or any request header and is not part of any build
  or check, and responses that need the user's sign-in are recorded by the owner with a debug-only tool in the app.
- **No fixture contains a real user's name, another personal value, a token or a client id.** The orchestrator
  reads recorded files before they are committed.
- Nobody calls the MAL API by hand with the client id. AniList needs no key, so an agent records its responses
  itself.
- A debug-only entry point that is started over `adb` exists only in debug builds and cannot be started by another
  app on the device.

### 11.2 Deterministic screenshots without a device

Screens are rendered on the JVM by Roborazzi on Robolectric, which draws Compose with the real Android rendering code.
Layout review works from these images. The harness and its tests say how a render works; this section says what a
render gives a reviewer and what it cannot show.

- **What is rendered.** The stateless screen with fixture states, inside the app's own theme and window setup, with
  the status bar and the gesture bar drawn. No view model, network or database is involved, every clock gives the
  fixtures' moment, and no image comes from MAL or elsewhere. Two renders of one commit on one machine are
  byte-identical.
- **Cases.** Each screen has one registry file, `<Screen>Screenshots`, which declares its cases: content, loading,
  empty and error, plus the cases its task needs. A screen that has no loading, empty or error state says why, and the
  render writes the reason to `<screen>/NOT_SHOWN.txt`. A **gallery** case renders every component of section 8.4 in
  all its states. A case can declare the keyboard open, or its own window size.

**Variants.** Every case is rendered in the five standard variants: `en-light`, `en-dark`, `ru-light` and `ru-dark`
on a phone-sized screen, and `ru-stress`, Russian on a small screen at a larger font scale. The content case also
gets `ru-large`, at a font scale of 2.0, and `en-alt`, with the second seed colour. The harness's variant list holds
their sizes.

**Captures.** In `app/build/outputs/roborazzi/<screen>/`, for each case and variant:

- `<case>__<variant>.png`: the viewport, as the user first sees it. Positions are judged here.
- `<case>__<variant>__full.png`: the whole scrollable content at full height, in every variant, whenever it is taller
  than the viewport; with a sheet or dialog open, that sheet's or dialog's own content.
- `<case>__<variant>__strip-<n>.png`, for the content case: the `n`th horizontal strip from the top, stitched so that
  every card shows. Magenta marks a strip that changed height between pages.
- `<case>__bounds.png`, for the content case in `en-light`: the bounds of the components, for checking alignment.
- `<case>__<variant>__frames.png`: a transition as frames in one image (section 8.5).
- `ACCESSIBILITY.txt`: the result of the automatic check for small touch targets and unlabeled controls, which
  fails the render.

`app/build/outputs/roborazzi/RENDERED_AT` holds the hash of the rendered commit and says "dirty" when the worktree had
uncommitted changes. A render never comes from the build cache, and it deletes all earlier images.

**What an image cannot show.** Robolectric renders at density 2.0, where phones run at 2.625 or 3, so hairlines and
sub-dp rounding differ. The status bar is always 24 dp, so a top padding written as 24 dp instead of the inset passes.
The keyboard is a block of fixed height, without keys or suggestions. Text scales the way Compose scales it at font
scales above 1.03, non-linearly, on every Android version. A looping animation is held at one frame, and a loop
written by hand is not detected.

Screenshots are build output and are not committed, except the calibration set of the rubric; CI uploads the full set,
rendered from GitHub's merge commit. Fonts rasterise differently between operating systems, so images are compared
only when rendered on the same machine.

## 12. Build, platform and distribution

- **`minSdk` 26** (Android 8.0): `java.time` without desugaring, notification channels and adaptive icons
  everywhere. The compile SDK is the one current Compose and `androidx.core` require, and the target SDK is the same.
- **Checks.** ktlint with its default rules and two exceptions: the package-name rule is off, because it rejects
  the underscore in the base package, and composable functions are exempt from the function-naming rule. Android
  Lint with the translation checks as errors; other warnings are not errors, because checks such as "newer version
  available" would fail the build whenever a library publishes a release.
- **CI** (GitHub Actions, repository `kauri-off/kiroku`) runs on every pull request and on pushes to `main`, and
  checks that the pull request title is a conventional commit; its workflow files say what it runs and keeps. What
  it builds for a pull request is built without any secret.
- **Release and distribution.** A version tag builds the release APK, with code and resource shrinking and with
  the MAL client id (section 3.3) taken from the repository secret `MAL_CLIENT_ID`, which is not a signing
  secret, and attaches it to a GitHub Release, where Obtainium can install it and keep it up to date. The build
  job signs the APK with a key made for that run; a separate job, the only one that sees the secrets of the
  `release` environment, re-signs it with the release key and runs nothing else: no Gradle, no cache, no
  third-party code. That environment is limited to `main` and version tags. Signing keys never enter the
  repository. F-Droid follows after 1.0. Kiroku is not published on Google Play.
- **Debug signing.** Debug builds from every checkout and worktree of the maintainer's machine are signed with
  one shared debug key, so that each installs over the other without losing data. Elsewhere the build falls back
  to the platform's default debug key.

## 13. External facts not yet verified

Each of these could change a decision above.

1. **The sign-in has not been run end to end.** The client is registered with the redirect URIs of section 3.1,
   but no token exchange without a secret has been made.
2. **The shapes of MAL's responses** for title statistics, related titles and the user's statistics are known from
   dailyAL's working code, not from MAL's reference, which shows no samples. The recorded responses decide.
3. **MAL's behaviour at its edges:** how it answers for a private list and whether that can be told apart from
   rate limiting (section 2.3 assumes the same refusal, told apart by the endpoint); its rate limits; whether the
   adult-content switch applies to related titles and recommendations inside a title; the shortest search query it
   accepts (believed to be three characters).
4. **AniList:** its API terms have not been read in full (assumed: a non-commercial open-source client may use
   it). How often its episode numbering differs from MAL's, and so how often A4 and A5 apply, is not measured.
5. **Android behaviour taken from documentation, not measured:** whether the network can be reached when an alarm
   fires in Doze (N3), and the real delay of inexact alarms.
6. **Sources named for after 1.0** (Jikan, AniList's links and characters, Shikimori) are unchecked; Jikan could
   not be reached from the maintainer's machine.
