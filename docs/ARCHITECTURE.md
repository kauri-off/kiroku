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
stack · 7 Persistence, errors and writes · 8 Design system · 9 How the interface is decided · 10 Localisation ·
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
redesign: at most five top-level destinations (section 6.3); a new data source is an optional section that can fail
without affecting its page (section 2.3).

## 2. Data sources

### 2.1 Decision

| Data | Source | Needs sign-in |
|---|---|---|
| Titles, search, rankings, seasons, related titles, status statistics, recommendations | MAL API v2 | No (client id only) |
| The signed-in user's list (read and write), own profile | MAL API v2 | Yes |
| Another user's public list | MAL API v2 | No (client id only) |
| Airing times of episodes | AniList GraphQL, keyed by MAL id | No |
| A title's score distribution (MAL's votes per score) | Tenrai, keyed by MAL id | No |
| Characters, videos, reviews, other users' avatar and "about" | None chosen (section 13); not in 1.0 | |

- Kiroku has no server of its own, scrapes no HTML and loads no remote configuration.
- AniList is chosen for airing times because it is a documented public API that knows real episode times, breaks
  and delays, and covers long-running shows regardless of season (dailyAL issue #8). MAL's own `broadcast` field (a
  weekday and a Japan-time slot) knows none of that and only feeds the estimate of section 4.
- **Privacy.** AniList receives the MAL ids of the unfinished titles on the user's list and of an airing title whose
  page is open, and nothing else about the user. Tenrai receives the device's IP address, the app's name and version, and
  the MAL id of each title whose MAL scores are opened. There is no analytics and no crash reporting.

### 2.2 External constraints and their consequences

MAL (from its API reference):

- The only user endpoint is `users/@me`, but anyone's public list can be read with the client id alone. Kiroku
  downloads another user's list and computes their statistics itself, with the same code as for the signed-in
  user. Their avatar, join date and "about" are not available and are not shown in 1.0.
- There is no user search: another user is opened by user name, in any letter case, or by a
  `myanimelist.net/profile/…` link.
- Title statistics are the status distribution and the member count; MAL's API has no per-score histogram.
- Search has no filter parameters, so 1.0 has no search filters.
- Dates may be partial ("2017-10-23", "2017-10", "2017").
- Entries flagged as not safe for work are returned only on request. **The user's own list always requests them,
  so an entry never vanishes from the list** (dailyAL issues #11, #60). Search and browse leave them out unless the
  setting "Show adult content in search" is on. Inside a title's page the setting changes only its recommendations:
  the title and its related titles are served either way.

AniList (from its API documentation and recorded answers):

- No authentication. A request without a `User-Agent` is refused. Its documented limit is 90 requests a minute, 30
  while its API is degraded; the app limits itself below that, and is conservative towards MAL, which publishes no
  limit.
- **Many titles that AniList marks as releasing have no schedule at all**, some MAL ids have no AniList entry and
  some have two, and AniList sometimes numbers episodes differently from MAL (a second season that continues at
  13, or one MAL title split into seasons that each start at 1). All are normal cases for section 4, not errors.

Tenrai (from its documentation and recorded answers):

- Its public limit is 4 requests a second and 120 a minute per IP address; a refusal is a 429 with `Retry-After`, or a
  403 from its anti-abuse system. The app asks one title at a time, well below that.
- It refreshes scores daily and caches answers up to 12 hours, so a distribution can be a day and a half old; an
  answer does not say how old it is.

### 2.3 When a source fails

| Source | Failure | What the user gets |
|---|---|---|
| MAL | Network or server error | Cached data stays, with a one-line snackbar: "No connection" when the device is offline, otherwise "Could not refresh". With nothing cached: the error state with Retry. A write is rolled back (section 7.3). |
| MAL | Answered as a private list when reading another user's list | "This list is private". Not treated as rate limiting. |
| MAL | Refused otherwise | Treated as rate limiting: background work backs off, and a read in the foreground says "MyAnimeList is refusing requests, try again later". A refused write is a failed write (section 7.3). |
| MAL | A value the app cannot read | The answer is used without that value, or with it filled in from what the answer has, and is reported. A report never stops the app. |
| AniList | Any failure or rate limit | Stored airing data stays in use, so planned notifications still fire. A title with no stored data shows the estimate (section 4). No message is shown. |
| An optional source (Tenrai's score distribution; others after 1.0) | Any failure, refusal or rate limit | Its part of the app says so and offers Retry, and never blocks or delays the page it belongs to. |

The app holds the permission `ACCESS_NETWORK_STATE`, and "offline" means the device has no network that reaches the
internet.

## 3. Sign-in with MAL

### 3.1 External constraints

From MAL's authorization reference:

- The only grant is the authorization code grant with PKCE, and **only the `plain` PKCE method**: the code
  challenge equals the code verifier, 43 to 128 characters. Kiroku's client has no secret. This is the strongest
  flow MAL offers: an app that intercepts the redirect cannot redeem the code without the verifier.
- The reference contradicts itself on the access token's lifetime, so Kiroku assumes no lifetime: it refreshes
  when MAL rejects the access token. The refresh token lives one month. A refresh returns a new pair and revokes
  the old one.

| Value | Release build | Debug build | Preview build |
|---|---|---|---|
| Application id | `io.github.kauri_off.kiroku` | `io.github.kauri_off.kiroku.debug` | `io.github.kauri_off.kiroku.preview` |
| OAuth redirect URI | `io.github.kauri-off.kiroku://oauth` | `io.github.kauri-off.kiroku.debug://oauth` | `io.github.kauri-off.kiroku.preview://oauth` |
| MAL client id | one registered client (app type "android", no secret) for all three | same | same |

- The three redirect URIs are the ones registered with MAL. **A redirect URI is its own constant per build type,
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
  cannot lose the session, and excluded, like the settings, from cloud backup and device transfer. Tokens that
  cannot be read never crash the app and never clear what is stored: the session is expired for that run. A store
  whose sign-in cannot be read either means signed out. A file that cannot be read at all signs nobody out: the
  session has no value until it is read.
- **S9.** Signing out removes the tokens and everything stored about the user.

### 3.3 The client id

- In a public client the id can be read out of the APK, so it is not a secret, but it is **never committed**: every
  fork registers its own. Agents never read or print it.
- The build takes it from the Gradle property `kiroku.malClientId`, else from the environment variable
  `MAL_CLIENT_ID`; a blank value counts as absent.
- A build without a client id succeeds. A **debug** or preview build without one starts in demo mode unless
  another source was chosen (section 6.4); a **release** build without one says so on the sign-in screen and starts
  no sign-in. Tests never need the id.

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
  the "Up next" order are computed in one place in `:core`, from stored data and an injected clock.
  Every screen, sort order and notification uses it; the list row and the title page show the same numbers.
- **A2. Correct between refreshes.** The aired count rises when a stored air time passes, with no network call, and
  a visible screen updates at that moment without polling.
- **A3. Totals.** The total is MAL's episode count when MAL has one, otherwise AniList's unless its schedule is not
  used or its numbering is doubted, otherwise unknown. A title MAL marks as finished has aired completely. The aired
  count never exceeds a known total. An airing status the app does not know is read as airing by every rule,
  freshness included.
- **A4. A schedule numbered differently is not used.** When AniList's episode numbers evidently continue an
  earlier season (they do not begin at episode 1, and run past MAL's total or reach a number that a show with
  MAL's start date cannot have reached), AniList's data is not used for that title.
- **A5. Doubt is shown as an estimate.** A count is estimated, not exact, when the two sources' totals differ, when
  AniList appears to have stopped tracking the title, or when AniList has two entries for the title whose
  numberings cannot be told apart; the estimate is then at least the other entry's highest aired episode. The
  estimate is AniList's own count, or more where its numbering is doubted, and stops at MAL's total.
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
  delay is configured, the device restarts or the app is updated. Four cases alert a second time, by decision: a
  notification dismissed between its post and its record, when the process dies or the store fails there; an
  episode announced from stored data whose re-check answered, after its bound, that it was postponed, when it
  airs; an episode announced again after any postponement; and an episode a run posted and did not record, which
  the user then marks watched and unmarks.
- **N2. Not lost.** A notifiable episode whose notification time has passed is announced the next time the app
  gets to run: the alarm, a device restart, an app update, the app being opened, or periodic background work that
  needs no network. A notification that would come more than three days late is not shown.
- **N3. Re-checked.** When the alarm fires and a network is available, the air time is checked again within a few
  seconds: a postponed episode is rescheduled instead of announced. When the check fails or takes too long, the
  stored data is used. An answer that comes after that and postpones the episode takes its notification back; if
  the airing source then puts the episode back at its first time, it is not announced again.
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

- **A notification** is one a title, in the preferred title language, in the language in effect when it is posted,
  with one action that marks the episode it names. Tapping it opens the title page with the List beneath it; a
  tapped notification goes and is not shown again.
- **Grouping.** All episode notifications share one group with a summary, so an evening with several shows is one
  stack. **Channel:** default importance.
- **First run.** Notifications are on by default. On Android 13 and later the system permission prompt appears
  once, on the List, right after the first successful sign-in, with no custom dialog before it. If the user
  denies, the setting shows "Blocked in system settings" with a button, and nothing else in the app mentions it.
  Planning continues either way, so a later grant needs no further step.
- **Delay.** A setting "Notify": "when the episode airs in Japan" (the default), "30 minutes later", "1 hour
  later", "3 hours later".
- **Limits the app cannot remove.** The system may defer the alarms and background work of an app the user rarely
  opens, and a force-stopped app receives nothing until it is opened. The notification settings say so and show
  which link broke when a notification is missing: the permission, exact alarms and the battery restriction, each
  with a link to the system screen, the last successful sync, the notifications coming next and those recently
  sent. They post a test notification through the real channel. This is what the owner checks during acceptance.

## 6. Structure and stack

### 6.1 Modules

| Module | Contains | Why separate |
|---|---|---|
| `:core` (Kotlin/JVM, no Android) | Domain types, the airing model, what is notifiable, the statistics calculator, the rules of list writes and syncs over a store and MAL, repository interfaces, typed errors; in its test fixtures, the fixture set, an in-memory store of the list and demo mode's repositories over them | Its tests are plain JVM tests that run in seconds. Rules that must have one definition live where no screen can re-implement them. |
| `:data` (Android library) | The MAL and AniList clients (the list's under `:core`'s list engine), sign-in and token storage, the database with the list's store and the store of airing data, the other repository implementations | Network and database types stay inside it, so the user interface depends only on `:core` types. |
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
- **On the List no row moves under a finger.** A tab's order is computed when the app starts, when its sort or
  direction is changed and on pull-to-refresh; at a start with no airing data stored, at a first download and on a
  pull it waits for the airing refresh that follows, however that ends, for a bounded time. An edit, a sync, an air
  time passing, a tab switch, the filter and coming back from a title page keep the order and the scroll position,
  and update rows in place. A row whose title no longer belongs to the tab leaves at once, and Undo puts it back in
  the same place; a title that joins the tab appears at the end until the order is next computed. After a row leaves,
  a "+1" on a row that moved up is not applied until the rows have stood still a moment.
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
  windows. Settings opens from Profile. Five destinations is the maximum.
- Each destination keeps its own back stack. Tapping the current destination again pops it to its root.
- Back from the root of Search or Profile goes to List; Back from the root of List leaves the app.
- A notification or a title's link builds the stack List → Title, and a profile's link List, then Profile → that
  profile, so Back never leaves the app unexpectedly.
- The app opens on List, tab Watching. Signed out, the List offers sign-in, and browsing without an account goes
  to Search.
- Predictive back is on.
- Deep links, from Android 12 once the user has allowed the app's links in the system's settings: a title's and a
  user's profile page on `[www.]myanimelist.net`; and the OAuth redirect.

### 6.4 Stack, wiring and demo mode

`gradle/libs.versions.toml` lists what is used and is the only place a version is written. What it cannot say is what
was chosen against, and why:

- **Navigation:** Jetpack Navigation 3. Navigation Compose is more machinery than this app needs.
- **Network:** OkHttp, Retrofit and kotlinx.serialization, with AniList queries sent as plain requests. Not Ktor (a
  second HTTP abstraction) and not Apollo (code generation and a plugin for a few queries).
- **Images:** Coil, fetching only through the app's HTTP client; Coil's own client and discovered components are not
  used.
- **Storage:** Room, with its annotation processor only in `:data`; DataStore Preferences for settings.
- **Sign-in:** Custom Tabs and the Keystore directly (`security-crypto` is deprecated).
- **Wiring** is done by hand. Not Hilt: a plugin and an annotation processor in every module for a graph of roughly
  fifteen objects.
- **Tests and checks:** JUnit 4, Robolectric, Roborazzi (Paparazzi's current line is alpha), ktlint, Android Lint
  (detekt's current line is alpha).
- **Not used at all:** a chart library, AppCompat, `kotlinx-datetime`, a paging library, Google Play Services,
  Firebase, any closed-source dependency (which keeps F-Droid possible).
- **Demo mode:** a debug or preview build chooses between the demo, the sandbox and the real account, each with its
  own stores, and without a choice starts on the demo when it has no client id. The demo runs entirely on the
  fixtures, held in memory, writes included; its data follows the device's date, so every weekday and weekly slot in
  it stays what it claims, and every "today" does on a device between UTC−9 and UTC+8, and a new process starts it
  again from the fixtures. Debug builds and the preview build have a debug menu that makes the demo or the sandbox
  fail and resets it; beyond it, the preview build behaves as the release build, except that it logs a line per
  request and per failed sign-in, keeps its reports in a file for the menu, and at its start removes a title a
  recording left on the list. Only a debug build stops at a report nobody expected. A release build holds none of it.
- Every request names Kiroku and its version in its `User-Agent`. Tokens and the client id never appear in logs. A
  write is never retried automatically, except the notification action (N9).
- From dailyAL come ideas and endpoint knowledge only. No code is ported and no asset is copied.

## 7. Persistence, errors and writes

### 7.1 Storage

- **The database is the single source of truth.** Screens read from it, and network results are written to it. It
  holds everything shown from cache: titles, the user's list, related titles, airing data, the record of announced
  episodes, the user's profile. Cached data is refreshed when it is shown stale, on pull-to-refresh, and by
  periodic background work.
- A screen counts as shown while it is on top and started, and as shown again only after its state's stop timeout;
  a load that failed for want of a connection is repeated once when the connection returns while it is shown.
- Held in memory only: search results, browse lists, other users' lists and statistics, titles' score distributions
  (kept for the session), and the airing data of A12.
- A value is stored under MAL's own code, so a code the app learns later needs no migration. An entry with a list
  status the app does not know stays on the List.
- A stored choice that cannot be read counts as never made and is reported; it stays in its file unless the file
  itself cannot be read.
- Every schema change is a real migration with a migration test, and a schema version, once on `main`, never
  changes. **There is no destructive fallback**: it would erase the record of announced episodes and announce
  them again (N10).

### 7.2 Errors and offline behaviour

- A screen has four states: content, loading (no content yet), empty, and error (no content and the load failed);
  one shared component draws the last three.
  **With cached content, a failed refresh is a snackbar, never a replaced screen.**
- Offline, the list, visited title pages, the airing counts and the statistics work from the database. Search shows
  the error state with "No connection" and Retry. A title page whose details were never loaded shows that error
  state where they would be, and a snackbar.
- All messages are snackbars or inline text. Toasts are not used. A snackbar outlives the screen that caused it, and
  one is shown at a time. One that is the only way back (a completion's Undo, a failed save's Retry) is never
  replaced, and another of its kind waits behind it: an Undo while its title's values are unchanged, a Retry while
  it has something left to set (W2). Its time runs only while the user can see and tap it; a finger holding any
  message stops its time, which starts again in full when let go. Any message, one that must stay too, can be swiped away; that ends it as its time running out does and
  runs no action. Ordinary messages are not queued.

### 7.3 Writes to the list

Writes are optimistic: an edit is visible at once and sent in the background. A write from a screen returns once it
is applied locally, and its failure reaches the user also after the screen that made the edit is gone. The
notification action instead waits for the server's answer (N9). "+1", the stepper and "Mark watched" are steps,
applied to the entry's values as they are when sent, not to values read earlier. A write sends only the values its
edit changed, so a value changed on the website since the last download, or one the app could not read, keeps what
MAL has. A step is written at once. The edit sheet writes nothing before Save, and is not dismissed with unsaved
changes without asking. "+1" on a row offers Undo, and so does a change on the title page that completes the title
(W7).

- **W1. In order.** Quick successive edits of one entry never overtake each other, and the server ends with the
  last value shown for each of the values they changed.
- **W2. A failure returns to the server's state.** When a write fails, the entry shows what the server last
  confirmed (not "the value before the tap"), no later edit of that entry that was still waiting is sent, and a
  snackbar says "Could not save · Retry", also when MAL refused the write; with the session expired it offers no
  Retry, as the banner offers sign-in (S6). Retry applies, as a new edit, each value the failed edits set, as shown
  just before the failure, that nothing has changed since; of the status, the count and the two dates it sets none
  once one it sets, or the status, has changed. A removal, once applied, is retried only while the entry is as it
  was. Its message goes once none is left. Example: the server has 12; two taps show 13, then 14; the request
  carrying 13 fails. 14 is not sent, the row shows 12, and Retry sends 14. A write MAL may have made (it failed with
  a server error or without a connection) is followed by a download.
- **W3. Undo is one write** that takes back the undone edit alone, applied to the values as they are: a step is
  undone by the opposite step, and any other value the edit set (status, dates, a typed count) goes back only
  while it still holds what the edit put there. A change off a status the app does not know offers no Undo, as that
  status is never sent to MAL.
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
- **W9. The app sets no ceiling on the watched count.** A user must be able to record what they watched: every
  write ("+1", "Mark watched", the stepper, the edit sheet) accepts any count of zero or more, above the aired count
  and the total included. MAL accepts such a write and stores no count above a total it knows, and the entry shows
  what MAL kept. "To watch" is then zero (A8).

## 8. Design system

Screens are composed of stock Material 3 Expressive components with their default shapes and behaviour, never
restyled into something else. This section holds the constraints on user interface code; what a screen shows is
decided as section 9 says.

### 8.1 Theme and window

- The Material 3 Expressive theme with its default shapes and motion scheme. Only the colours are Kiroku's.
- Material 3 is pinned ahead of the Compose BOM while the version with Expressive is a release candidate, and its
  experimental API is opted into in one file, so that what may break on an update is in one place.
- **Dynamic colour** is on by default on Android 12 and later, and light and dark follow the system; a setting
  overrides each. Below Android 12 and in screenshot tests the scheme comes from one seed colour, which is not MAL's
  blue. One screenshot variant uses a second, contrasting seed to expose misused colour roles.
- Colours are used only through the theme's colour roles.
- **Edge-to-edge.** Content is never hidden under a system bar: the last row of every list can be scrolled clear of
  the gesture bar, and a snackbar never sits under it.
- Content has a maximum width and is centred on wider windows.
- The launcher icon is original: no characters, no MAL name or logo.

### 8.2 Typography

The default Material 3 Expressive type scale with the system font. A component keeps the type role it sets.

### 8.3 Spacing and accessibility

- Layout code uses only the `Spacing` tokens, the theme's named sizes and component defaults: no literal dp value or
  colour outside the theme.
- Every tappable element is at least 48 × 48 dp and every control has a label; the render fails otherwise (section
  11.2). An action behind a long press is offered to screen readers too.
- **Large text** is a font scale of 1.5 or more. No text or control is clipped at any font scale up to 2.0.

### 8.4 Components

A screen uses stock Material 3 components directly, and the shared components of `design/component`, each built
from stock ones and rendered in the gallery (section 11.2). No control is drawn by hand. Icons are Material icons.

### 8.5 Motion

Stock components keep their own motion. For what the app animates beyond that:

- **One source.** Every animation the app writes takes its spec from the theme's motion scheme, and only from its
  effects springs. One value is the owner's exception: the List's pages settle and slide with ViewPager2's snap
  timing, so that the gesture feels as in Droid-ify.
- **Behaviour.** State changes at once and the animation only smooths it. Every animation can be interrupted and
  reversed mid-flight, ends in the state of the last input, and never blocks a tap. With the system's animations
  turned off, every screen is complete and correct immediately. An idle screen has no animation running, and
  nothing loops except a progress or loading indicator while work runs.

A task that adds or changes an animation shows with tests that it settles within the bound the motion tests set for
its spring, that re-triggering it mid-flight ends in the right state, that the interaction is correct with
animations off and that the idle screen has nothing pending. It also renders the transition as a frames image next
to the screen's screenshots (section 11.2), and the hand-over names the transitions that changed. A state change
that a stock component answers with motion of its own gets the frames image, without the tests.

## 9. How the interface is decided

What a screen shows and how it is arranged is the owner's decision, and he makes it by looking: at the running app,
or at the screenshots of section 11.2. No tracked text prescribes it: there is no list of what a screen must show,
no limit on it and no scoring of layouts.

An agent that builds or changes a screen builds one version, within the constraints of sections 6.2, 7, 8 and 10,
and shows it. The owner answers with feedback; variants are made only when he asks for them. How a transition
feels, and the back gesture under a finger, he judges from the installed app.

The code and the screenshots rendered from `main` are the record of what was decided.

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
  or check; those needing the user's sign-in, by the owner with a debug tool, as sent less token and client id.
- **No fixture contains a real user's name, another personal value, a token or a client id.** The orchestrator
  reads recorded files before they are committed. The owner's stay outside all checkouts; tests skip without them.
- Nobody calls the MAL API by hand with the client id. AniList and Tenrai need no key, so an agent records their
  responses itself.
- A debug-only entry point that is started over `adb` exists only in debug builds and cannot be started by another
  app on the device.

### 11.2 Deterministic screenshots without a device

Screens are rendered on the JVM by Roborazzi on Robolectric, which draws Compose with the real Android rendering code.
The interface is looked at in these images (section 9). The harness and its tests say how a render works; this
section says what a render gives and what it cannot show.

- **What is rendered.** The stateless screen with fixture states, inside the app's own theme and window setup, with
  the status bar and the gesture bar drawn. No view model, network or database is involved, every clock gives the
  fixtures' moment, and no image comes from MAL or elsewhere. Two renders of one commit on one machine are
  byte-identical.
- **Cases.** Each screen has one registry file, `<Screen>Screenshots`, which declares its cases: content, loading,
  empty and error, plus the cases its task needs. A screen that has no loading, empty or error state says why, and the
  render writes the reason to `<screen>/NOT_SHOWN.txt`. A **gallery** case renders every shared component in all
  its states. A case can declare the keyboard open, its own window size, or `ru-large` as well.

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
- `<transition>__<variant>__frames.png`: a transition as frames in one image (section 8.5).
- `ACCESSIBILITY.txt`: the result of the automatic check for small touch targets and unlabeled controls, which
  fails the render.

`app/build/outputs/roborazzi/RENDERED_AT` holds the hash of the rendered commit and says "dirty" when the worktree had
uncommitted changes. A render never comes from the build cache, and it deletes all earlier images.

**What an image cannot show.** Robolectric renders at density 2.0, where phones run at 2.625 or 3, so hairlines and
sub-dp rounding differ. The status bar is always 24 dp, so a top padding written as 24 dp instead of the inset passes.
The keyboard is a block of fixed height, without keys or suggestions. Text scales the way Compose scales it at font
scales above 1.03, non-linearly, on every Android version. A looping animation is held at one frame, and a loop
written by hand is not detected.

Screenshots are build output and are not committed; CI uploads the full set,
rendered from GitHub's merge commit. Fonts rasterise differently between operating systems, so images are compared
only when rendered on the same machine.

## 12. Build, platform and distribution

- **`minSdk` 26** (Android 8.0): `java.time` without desugaring, notification channels and adaptive icons
  everywhere. The compile SDK is the one current Compose and `androidx.core` require, and the target SDK is the same.
- **Checks.** ktlint with its default rules and two exceptions: the package-name rule is off, because it rejects
  the underscore in the base package, and composable functions are exempt from the function-naming rule. Android
  Lint with the translation checks as errors; other warnings are not errors, because checks such as "newer version
  available" would fail the build whenever a library publishes a release.
- **CI** (GitHub Actions, repository `kauri-off/kiroku`) runs on every pull request and push to `main` that changes
  more than documents, and checks every pull request's title as a conventional commit; its workflow files say what
  it runs and keeps. What it builds for a pull request is built without any secret.
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

1. **A sign-in with the app killed while the browser is open has not been run on a phone.** A sign-in has: MAL
   granted a token with a lifetime.
2. **MAL's behaviour at its edges:** its rate limits, and what it answers when it limits requests: a private list
   is told by the answer recorded for one, a refusal with its own error code, and a limit answered in the same words
   would read as a private list; the shortest search query it accepts (believed to be three characters); what its
   website changes by itself when an edit completes a title, which W7 assumes (through the API it changes nothing by
   itself).
3. **AniList:** its API terms prohibit its use "within competing, non-complementary services of the same nature",
   anime list and tracker services included, for all its data. The owner reads Kiroku, which keeps no list at
   AniList and is open source and non-commercial, as complementary, and the stored schedules of the tracked titles,
   forgotten at sign-out, as a cache rather than the data storage the terms prohibit; he accepts the risk that
   AniList reads its terms otherwise. How often its episode numbering differs from MAL's, and so how often A4 and A5
   apply, is not measured.
4. **Android behaviour taken from documentation, not measured:** whether the network can be reached when an alarm
   fires in Doze (N3), and the real delay of inexact alarms.
5. **Sources beyond MAL and AniList.** Jikan's public API shut down on 1 October 2026: the score distribution comes
   from Tenrai, and characters, videos, reviews and other users' avatars have no source. Tenrai is in beta and does
   not say who runs it. Like every source of MAL's score distribution it serves what it reads from MAL's website,
   which clause 4(i) of MAL's API agreement rules out for an app's own requests; the owner accepts that risk, and the
   app itself scrapes nothing. AniList's links and characters and Shikimori are unchecked.
