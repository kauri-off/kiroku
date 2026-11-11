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
- When the code or a device shows this document to be wrong, it is corrected in the same pull request, by
  shortening or restating it, not by describing the implementation.

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
| MAL | Network or server error | Cached data stays, with a one-line "Could not refresh" snackbar. With nothing cached: the error state with Retry. A write is rolled back (section 7.3). |
| MAL | Refused when reading another user's list | "This list is private". Not treated as rate limiting. |
| MAL | Refused anywhere else | Treated as rate limiting: background work backs off, and the foreground says "MyAnimeList is refusing requests, try again later". |
| AniList | Any failure or rate limit | Stored airing data stays in use, so planned notifications still fire. A title with no stored data shows the estimate (section 4). |
| An optional source (after 1.0) | Any failure | Its section collapses to one line, "Unavailable · Retry", and never blocks or delays the rest of the page. |

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
| `:core` (Kotlin/JVM, no Android) | Domain types, the airing model, what is notifiable, the statistics calculator, repository interfaces, typed errors; in its test fixtures, the fixture set and demo mode's in-memory repositories over it | Its tests are plain JVM tests that run in seconds. Rules that must have one definition live where no screen can re-implement them. |
| `:data` (Android library) | The MAL and AniList clients, sign-in and token storage, the database, the repository implementations | Network and database types stay inside it, so the user interface depends only on `:core` types. |
| `:app` (Android application) | Theme and components, screens and view models, navigation, notifications and background work, wiring | The only module that knows Compose. |

- Dependencies point one way: `app → data → core` and `app → core`. The base package is
  `io.github.kauri_off.kiroku`. Failures cross a module boundary as typed errors, not exceptions.
- The fixtures and demo mode serve `:core` tests, previews, screenshot tests, view-model tests and debug builds.
  Release code cannot see them, and a release build that would contain them fails.
- One module per feature was rejected: with about ten screens it adds build configuration and no protection that
  three modules do not already give.

### 6.2 State

- A screen is a stateless composable, `XScreen(state, onEvent)`, drawn entirely from one immutable state value. It
  never touches a view model, a repository or navigation. This is what previews and screenshot tests render.
- A view model builds that state from database flows and handles the events. A thin `XRoute` connects the view
  model to the screen and to navigation, and is the only place that knows either.
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

- **User interface:** Jetpack Compose with Material 3 and its adaptive navigation suite. **Navigation:** Jetpack
  Navigation 3 (runner-up: Navigation Compose, more machinery than this app needs).
- **Network:** OkHttp, Retrofit and kotlinx.serialization; AniList queries are sent as plain requests. Rejected:
  Ktor (a second HTTP abstraction) and Apollo (code generation and a plugin for a few queries). **Images:** Coil.
- **Storage:** Room, with its annotation processor (KSP) only in `:data`; DataStore Preferences for settings.
- **Background:** WorkManager, and `AlarmManager` for the notification alarm. **Sign-in:** Custom Tabs and the
  Keystore directly (`security-crypto` is deprecated).
- **Wiring** is done by hand. Hilt was rejected: a plugin and an annotation processor in every module for a graph
  of roughly fifteen objects.
- **Demo mode:** a debug build can run entirely on the fixtures, held in memory, writes included. A debug build
  without a client id starts in it. Demo data follows the device's date: the fixtures are shifted by the whole
  number of weeks since their reference moment, and demo mode runs on a clock that lags the device's by the rest
  of that week, so every weekday and weekly slot in them stays what it claims; every "today" does too on a device
  between UTC−9 and UTC+8, which includes the fixtures' own zone. Its server is in memory too, so a new process
  starts again from the fixtures.
- **Tests and checks:** JUnit 4, Robolectric, Roborazzi (Paparazzi's current line is alpha), ktlint, Android Lint
  (detekt's current line is alpha).
- Also not used: a chart library (bars are `StatBar`), AppCompat, `kotlinx-datetime` (`java.time` is available), a
  paging library, Google Play Services, Firebase, any closed-source dependency (which keeps F-Droid possible).
- Versions are written only in `gradle/libs.versions.toml`.
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
- Offline, the list, visited title pages, the airing counts and the statistics work from the database. Search and
  unvisited title pages show the error state with "No connection" and Retry.
- All messages are snackbars or inline text. Toasts are not used.

### 7.3 Writes to the list

Writes are optimistic: an edit is visible at once and sent in the background. A write from a screen returns once it
is applied locally; a failure is reported on a stream of the list repository, with what Retry needs, so it reaches
the user after the screen that made the edit is gone; with nothing collecting it, it is dropped, as the entry is
already back at the server's state. The notification action instead waits for the server's answer (N9). "+1" and
"Mark watched" are steps, applied to the entry's values as they are when sent, not to values read earlier.

- **W1. In order.** Quick successive edits of one entry never overtake each other, and the server ends with the
  last value shown.
- **W2. A failure returns to the server's state.** When a write fails, the entry shows what the server last
  confirmed (not "the value before the tap"), no later edit of that entry that was still waiting is sent, and a
  snackbar says "Could not save · Retry". Retry applies, as a new edit, the values shown just before the failure.
  Example: the server has 12; two taps show 13, then 14; the request carrying 13 fails. 14 is not sent, the row
  shows 12, and Retry sends 14.
- **W3. Undo is one write** that restores every value the undone edit changed: count, status and dates.
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

The rule behind every choice: stock Material 3 components with default shapes and behaviour, composed, never
restyled into something else.

### 8.1 Theme and window

- The Material 3 theme with default typography and shapes.
- **Dynamic colour** is on by default on Android 12 and later, with a setting to turn it off. Below Android 12 and
  in screenshot tests the app uses a fixed light and dark scheme generated from one seed colour, `#00696D` (a teal,
  deliberately not MAL's blue). One screenshot variant uses a second, contrasting seed, `#8B5000`, to expose
  misused colour roles.
- Light and dark follow the system; a setting overrides (System, Light, Dark).
- Colours are used only through the theme's colour roles. Emphasis uses `primary`; "to watch" uses `tertiary`;
  errors use `error`.
- **Edge-to-edge.** Content is never hidden under a system bar: the last row of every list can be scrolled clear of
  the gesture bar, and a snackbar never sits under it.
- **Launcher icon:** an original adaptive icon built from the character 記, with no MAL name or logo.

### 8.2 Typography

The default Material 3 type scale with the system font. Screens use only these roles:

- `headlineSmall`: the title on the title page. `titleMedium`: section headers, the title in the edit sheet and the
  number in `StatNumber`.
- `bodyMedium`: body text and supporting lines. `labelLarge` and `labelMedium`: captions.
- Top app bar titles, row headlines, buttons and chips keep the role their component sets.

### 8.3 Spacing and shape

A 4 dp grid with the `Spacing` tokens `xs` 4, `s` 8, `m` 12, `l` 16, `xl` 24, `xxl` 32. Layout code uses only these
tokens, the theme's named sizes and component defaults. A literal dp value outside the theme fails a test; a literal
colour outside the theme is a code-review finding.

- Horizontal screen padding: `l`. Between sections: `xl`. Between related elements inside a section: `s`.
- Content is at most 640 dp wide and centred on wider windows.
- Every tappable element is at least 48 × 48 dp.
- Posters are 2:3 with the theme's small corner shape, in three sizes: 56 × 84 dp in rows, 104 × 156 dp in strips
  at font scale 1.0, 120 × 180 dp in the title header.
- **A caption of a strip card is never broken inside a word.** The card and its poster are 104 dp wide at font
  scale 1.0 and grow in proportion to the font scale above it, so that a one-word caption fits at every scale; this
  is a rule of the component, and a screen never passes a width.
- Cards of a strip are `m` apart.
- **Where a title must break, it breaks after a hyphen rather than inside a hyphenated segment**, wherever it is
  shown ("Tenkuu-Toshi-Rondenia-no-" / "Kuuchuu-…"), unless the segment alone is wider than the line.
- **Large text** means a font scale of 1.5 or more. With it the three numbers of the Profile summary are stacked
  one per line (section 9.7). Nothing else depends on the font scale.

### 8.4 Components

The complete list. Adding one needs a reason in the pull request, an entry here and a gallery screenshot.

| Component | Built from | Purpose |
|---|---|---|
| `Poster` | An image | A 2:3 image with a placeholder in a surface colour role; no text over it, ever |
| `AnimeRow` | `ListItem` | Poster, headline, up to two supporting lines, one optional trailing element |
| `PosterCard` | `Poster` and text | For horizontal strips: a poster, a title (line cap two), caption 1 (line cap two) and caption 2 (line cap one). All cards of a strip have one height, whatever their text |
| `SectionHeader` | Text and `TextButton` | Section title with an optional "See all" |
| `StatBar` | Text and `LinearProgressIndicator` | One labelled bar of a distribution, static. A screen reader hears it as one sentence ("Completed, 1,234,567, 45 percent") |
| `StatNumber` | Two texts | A number with a caption (the MAL score; the Profile summary) |
| `FactRow` | `ListItem` | A label and a value |
| `EpisodeStepper` | `IconButton`, `OutlinedTextField`, `IconButton` | Minus, a numeric field with "/ 24", plus. Typing a number is how a user catches up many episodes. A typed value is applied when the user confirms it or leaves the field, not per keystroke, except in the edit sheet, which writes nothing before Save and applies it as it is typed. The count has no ceiling (W9): "25 / 24" is a legal display, plus stays enabled at the total, and nothing is refused; only digits are taken, an empty field is not a count, and the length is limited |
| `ScreenState` | `CircularProgressIndicator`, text, `Button` | The loading, empty and error states, identical everywhere |
| `SessionBanner` | `Surface`, text, `TextButton` | "Session expired · Sign in" |

Stock Material 3 components are used directly for everything else: scaffold, top app bar, navigation suite, search
bar, tab row, modal bottom sheet, chips, buttons, list items, text fields, switch, slider, date picker, dialogs,
snackbars, pull-to-refresh, progress indicators. Icons are Material icons.

Not allowed anywhere: custom-drawn buttons or pills, cards used as decoration, gradients, shadows beyond component
defaults, shimmer placeholders, text or badges drawn over images, chips that do nothing when tapped, auto-scrolling
carousels, more than one floating action button (1.0 uses none), a tab row together with another navigation level
for the same content, second-by-second countdowns.

### 8.5 Motion

Motion is functional and sparse. Stock Material 3 components keep their own motion. Anything beyond that needs a
purpose stated in the pull request: feedback, showing where something came from or went, making a state change
legible, or preventing a jump. Decoration is not a purpose.

- **Not animated:** row taps, "+1", typing in a filter, a data refresh, re-entering a screen, numbers counting up,
  anything looping except a progress indicator while work runs. No parallax, no full-screen zoom or rotation, no
  bounce, no staggered lists, no custom press effect (the ripple is the feedback).
- **One source.** Durations, easings and springs come from the theme's Material motion scheme and tokens, kept in
  one place. A literal duration, curve, spring value or delay in user interface code fails the build. Specs that
  can overshoot move, resize and reshape; they are never used on opacity or colour.
- **Patterns.** List to title page: the Material forward and backward transition. Destinations of the navigation
  bar: fade through. Sheets, menus and snackbars enter from their edge and leave the way they came. Lists have
  stable keys and use the list's item animation for add, remove and move. Content that finishes loading fades in.
  One thing moves per change; a container that enters brings its content with it.
- **Time.** An exit is shorter than the matching entrance. A change inside a component settles within 200 ms, a
  component's entrance within 300 ms, a screen's entrance within 400 ms.
- **Behaviour.** State changes at once and the animation only smooths it. Every animation can be interrupted and
  reversed mid-flight, ends in the state of the last input, and never blocks a tap. Predictive back shows where
  Back leads, and a cancelled gesture restores the previous state exactly. With the system's animations turned off,
  every screen is complete and correct immediately. An idle screen has no animation running.

A task that adds or changes an animation shows, with tests on the test clock, that it settles within its time,
that re-triggering it mid-flight ends in the right state, that the interaction is correct with animations off, and
that the idle screen has nothing pending. It also renders the transition as four frames in one image (start, one
third, two thirds, end), `<case>__<variant>__frames.png`, next to the screen's screenshots: the first and last frames equal the static renders, and
direction and pattern match this section. The hand-over names the transitions that changed. How a transition
feels on a device, and predictive back under a finger, are judged by the owner from the installed app.

## 9. Per-screen information budgets

### 9.1 How a budget works

Each screen's budget has three parts, and the layout rubric scores against all three:

- **Must show:** elements that have to be present whenever they apply. Leaving one out fails the screen.
- **Limits:** the most the screen may show before the user taps anything. Exceeding one fails the screen.
- **Behind a tap:** where everything else lives.

The terms **logical line**, **line cap**, **ellipsis**, **base variants** and **content from MAL** are defined in
`docs/LAYOUT_RUBRIC.md` section 2 and are used here in that sense.

The strings quoted in the budgets are product decisions, given in English; `docs/GLOSSARY.md` has the Russian form
of each. Whether a string fits where its budget puts it is proven by the render. **A string that does not fit is
budget feedback for the owner, never something the author solves with an ellipsis.**

**Extending a budget** (for features after 1.0) is a pull request that changes this section first: it adds the
element to Must show or to the limits of one screen, says what it displaces or why the screen can afford it, and is
approved by the owner before the layout task starts. New content defaults to "behind a tap". The list row never
gains a line.

### 9.2 Sign-in

Must show: the app name; one sentence; a "Sign in with MyAnimeList" button; a "Browse without an account" text
button; the line "Unofficial. Not affiliated with MyAnimeList." Limit: nothing else. In a build without a client id
(section 3.3) the sentence is replaced by "This build has no MyAnimeList client id" and the sign-in button is
disabled.

### 9.3 List (the start screen)

- Top app bar: the title "My list", a filter-by-text icon, a sort icon.
- A scrollable tab row: Watching, Planned, Completed, On hold, Dropped. No counts.
- Rows (`AnimeRow`), at most three logical lines and one action.

**Must show per row:** the title (line cap two, then an ellipsis); line 2; line 3 when it applies; in the Watching
tab, one "+1" tonal icon button. The three logical lines are the title, line 2 and line 3, so a row with a two-line
title and both lines is within the limit.

| Case | Line 2 | Line 3 |
|---|---|---|
| Airing, exact count, episodes to watch | `12 / 24 · 14 aired` | `2 to watch`, in the `tertiary` colour |
| Airing, exact count, nothing to watch | same | the next episode, `Episode 15 · Sat 17:30`; none when no next episode is known |
| Airing, estimated count, episodes to watch | `12 / 24 · ~14 aired` | `~2 to watch`, in the `tertiary` colour |
| Airing, estimated count, nothing to watch | same | none |
| Finished, or aired count unknown | `12 / 24` | none |
| Not yet aired | `Premieres Sat 17:30` when the first episode's time is known, written by the air-time rule of section 10; otherwise MAL's date by its precision, `Premieres 4 Jan`; `Not yet aired` when there is neither | none |
| Completed tab | `Score 8` (or `Not scored`) | none |

"Episodes to watch" means a "to watch" figure above zero (A8), which only a title in Watching can have; in the other
tabs an airing title is always a "nothing to watch" row. An unknown total is written without the slash part:
`12 · 14 aired`. Line 3 restates a difference the user could compute from line 2; that is intentional, it is the
one thing the screen exists to highlight.

**Limits:** not in a row: MAL score, member count, genres, media type, studio, tags, a countdown, an edit button, a
progress bar.

**Default order.** Watching is sorted by default as "Up next", in three groups:

1. titles with episodes to watch, exact or estimated: the one whose latest episode aired most recently on top (a
   title for which that time is not known goes to the end of the group);
2. then titles with a known next episode, the soonest first;
3. then the rest, by last update, newest first.

Equal keys are ordered by title. The other tabs default to "Last updated". The sort chosen for each tab is
remembered.

**Rows never move under the user's finger.** The order of a tab is computed when the tab is shown (the List is
opened or returned to, or the tab is selected), when its sort is changed, and on pull-to-refresh. It is not
computed after each edit. Data that changes while the tab is visible (an edit, a sync, an air time passing) updates
rows in place without moving them: after "+1" a row stays where it is, under "Up next" and under "Last updated"
alike. Two things do change the visible rows: a row whose title no longer belongs to the tab leaves at once, with
the list's item animation (the usual case is a "+1" that completes a title, and Undo puts it back in the same
place); a title that a sync adds to the tab appears at the end until the order is next computed.

**Behind a tap:** the row opens the title page. Filter replaces the top app bar title with a text field that
filters the current tab by title; Back or clearing the field ends it. Sort opens a bottom sheet with at most five
options (Up next, Title, Last updated, Score, Start date). "+1" answers with a snackbar "Episode 13 watched · Undo".
When "+1" reaches the total, the title becomes Completed (W7): the row leaves Watching at once and the snackbar says
"Moved to Completed · Undo". Undo puts back the count, the status and the dates, and the row returns to its place.

**Read-only variant** (another user's list): the top app bar title is "<name>'s list" with Back; line 2 is their
progress or score; there is no line 3 and no "+1", because no airing data is fetched for someone else's titles.

### 9.4 Title page

One scrolling column in this fixed order. No tabs, no floating buttons. **Each fact is stated once on the page**:
the episode total, the year and a related title appear in one place only, as the items below say.

1. **Top app bar:** Back, and an overflow menu with "Open on MyAnimeList" and "Share". When the session has expired
   (S6), the `SessionBanner` under it, from the page's own state, while loading and on error too.
2. **Header.** Must show: the poster; the full title with no line cap; the facts line, "TV · 2024 · 24 episodes";
   a `StatNumber` with the MAL score and the caption "MAL score" ("—" when there is none); the rank as its own short
   line, "Ranked #12" (absent when MAL gives none). Limits: the facts line holds at most three facts (type, year,
   episode count). The episode count is left out when it is 1 or unknown (a film reads "Movie · 2024") and when the
   title is on the list, whose progress states it; the year is left out when the airing block's premiere states it.
   The header holds nothing besides these five elements.
3. **List block.** Not on the list: one filled button "Add to list" across the content width. It adds the title as
   Planned and shows the snackbar "Added to Planned · Change", where "Change" opens the edit sheet. On the list, must
   show, as one block: the status as a tonal button across the content width (where "Add to list" stands otherwise)
   that opens the edit sheet; under it, side by side and each under its own label, the `EpisodeStepper` under
   "Episodes watched", whose changes are written at once, and the user's score under "Your score", or a "Rate" text
   button (both open the edit sheet). The score's column starts at the same place on every title; with large text it
   may move under the stepper. The stepper has no ceiling (W9). A stepper change that reaches or passes MAL's total
   completes the title (W7) and shows the snackbar "Moved to Completed · Undo", as on the List; other changes show no
   snackbar.
4. **Airing block** (must show for titles that are airing or not yet aired; absent otherwise). Limit: two logical
   lines, as in this table.

   | Case | Line 1 | Line 2 |
   |---|---|---|
   | Airing, exact count, episodes to watch | `14 aired · 2 to watch` | the next episode, `Episode 15 · Sat 17:30`; none when no next episode is known |
   | Airing, exact count, nothing to watch or not on the list | `14 aired` | same |
   | Airing, estimated count, episodes to watch | `~14 aired · ~2 to watch` | none |
   | Airing, estimated count, nothing to watch or not on the list | `~14 aired` | none |
   | Airing, aired count unknown | `Airing` | none |
   | Not yet aired | `Premieres Sat 17:30` (beyond six days `Premieres 4 Jan, 17:30`) when the first episode's time is known, by the air-time rule of section 10; otherwise `Premieres 4 Jan 2027`, `Premieres January 2027` or `Premieres 2027` by the precision of MAL's date (section 10); `Not yet aired` when there is neither | none |

   The premiere is stated once: when AniList's time is known it replaces MAL's date, which is a Japanese calendar
   date and can differ from the device's by a day. The aired count is written without the total, which the list
   block or the header states.
   The "to watch" part is in the `tertiary` colour. The list row and this block show the same numbers for the same
   title (A1), also for a title outside the tracked set (A12).
5. **Sequel** (must show when the title has a direct sequel; absent otherwise): a `SectionHeader` "Sequel" over one
   `AnimeRow` that opens it: its title, "TV · 2026", and the user's list status for it when it is on their list.
   Which sequel is decided in `:core` from the related titles: of several, the one that starts first. It is not
   repeated in the Related strip. Following the chain of sequels across titles is not on the page in 1.0.
6. **Synopsis:** a `SectionHeader` "Synopsis" over the text, line cap four, with "More" to expand in place and
   "Less" to collapse it again. Under
   it one line of plain text with the genres ("Action · Fantasy · Drama"), line cap one. Without a synopsis, the
   genres line stands alone, with no header.
7. **Related** (must show when the title has related anime other than the sequel): `SectionHeader` "Related". A horizontal strip of at
   most ten `PosterCard`s. When none of them fits the strip (only "other" and "character"), the header with "See all"
   stands alone.
   - The card's title: line cap two.
   - Caption 1 is the relation ("Sequel"), line cap two. The two longest names use the short strip labels of the
     glossary ("Alt. version", "Alt. setting"); the full names are used on "See all".
   - Caption 2, line cap one: the user's list status for that title ("Completed", "Watching"), without numbers, when
     it is on their list; otherwise "TV · 2019".
   - Order: prequel, sequel, parent story, full story, side story, spin-off, alternative setting, alternative
     version, summary; within a type by start date. The types "other" and "character" appear only on "See all".
   - "See all" is shown whenever anything other than the sequel row's title is not in the strip. Related manga are not shown in 1.0.
8. **Statistics** (must show): five `StatBar`s (Watching, Completed, On hold, Dropped, Planned) with counts and
   percentages, and one line with the member count.
9. **More:** rows that open sub-screens: "Details", and "Recommendations" when MAL gives any.

**Above the fold:** on a 411 × 891 dp screen the header, the list block and the airing block are visible without
scrolling, for a title of up to two lines.

**Loading.** Opened from a list or search row, the page already has the summary: header, list block and airing
block render at once, and one linear progress indicator under them stands in for items 5 to 9 until the details
arrive. Only a page opened with no data at all (a deep link) uses the full-screen loading state.

**Not on the page in 1.0:** reviews, news, forum topics, videos, pictures, characters.

**Adding a section later.** A new section starts as a row in "More" and opens a sub-screen. It is promoted onto the
page, between Statistics and More, only through the procedure of section 9.1, with its own Must show and limit.
The order of items 2 to 8 does not change, and the above-the-fold rule stays.

### 9.5 Edit sheet

A modal bottom sheet. Must show: the anime's title (line cap one), so a sheet opened from a list row says what it
edits; the status as five single-choice chips in a wrapping row (two rows in the base variants, more with larger
text; chips are controls, not logical lines); an `EpisodeStepper` labelled "Episodes watched"; a score slider 0 to 10
labelled "Score", with the score names of the glossary; start and finish dates; a text button "Remove from list"
(asks for confirmation); "Save", on screen without scrolling whenever the keyboard is closed (while the keyboard is
open, "Save" and "Remove from list" are hidden; Done, Back or a tap on another control closes it). Limit: nothing
else. Nothing is written before "Save", and "Save" writes exactly what the sheet shows: a typed count takes effect in
the sheet as it is typed, and an empty field shows and holds the values from before typing began, taking back any
automatic change an earlier keystroke caused. "Save" is disabled only while the count field is empty. The stepper's
total is the displayed total (A3) and does not bound the count (W9); the automatic status change is decided on MAL's
total (W7). Dismissing the sheet with unsaved
changes, by swipe or Back, asks "Discard changes?". Rewatching, tags, comments and priority come after 1.0.

### 9.6 Search

- A Material search bar at the top.
- Before a query, must show: up to five recent searches, then four rows that open browse lists: "This season", "Top
  airing", "Top rated", "Most popular". Limit: nothing else; there is no home feed. Later browse entries are added
  as rows here.
- Results (`AnimeRow`), must show: the title (line cap two); the facts line of section 9.4, "TV · 2024 ·
  24 episodes"; when the title is on the user's list, a second supporting line with the list status ("Watching ·
  12 / 24"; dailyAL issue #67); trailing, the MAL score.
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

Each is a top app bar with Back and one list. Limit for all: nothing but the list and its group headers.

| Sub-screen | Must show | Row limits |
|---|---|---|
| Related: see all | Every related anime, grouped under `SectionHeader`s by relation type (full names) in the order of section 9.4, then "Other" and "Character" | `AnimeRow` as in search results: the title (line cap two); "TV · 2019"; the list status line ("Watching · 5 / 12") when the title is on the user's list; no trailing element |
| Details | `FactRow`s: alternative titles, dates, source, studios, rating, episode length, broadcast time. A fact MAL does not give is left out | One label and one value per row; a value has line cap three |
| Recommendations | The titles MAL recommends | `AnimeRow` exactly as in search results, including the trailing MAL score |
| Score distribution, Genres, Formats and years | `StatBar`s computed from the list; "Formats and years" has two groups | One `StatBar` per row; scores and years in their own order, everything else the largest first; at most twenty bars per group |

## 10. Localisation

- English strings are the default resources, Russian the `ru` ones. Both are written by hand. `docs/GLOSSARY.md`
  fixes the Russian term for every recurring concept and is required reading for any task that touches strings.
  The owner reviews the Russian text at the end of each milestone.
- **No user-visible string exists outside the `:app` resources**, and strings are split into one file per feature,
  so parallel tasks do not edit the same file. The build fails on a missing or extra translation and on a missing
  plural form, and a test fails on a string literal in user interface code (Android Lint does not see Compose).
  That test exists before the first screen is written.
- A line never breaks between a number and its word, unit or slash: `24 episodes`, `12 / 24`, `К просмотру: 2`.
- **Values from MAL that the user reads as interface text are translated by the app:** list status, airing status,
  media type, season ("Fall 2024" / "Осень 2024"), source, age rating, relation type, and genres. An unknown value
  falls back to MAL's code written as words ("light_novel" as "Light novel"), and an unknown genre to its MAL name.
  This fallback is the only interface text not from the resources. Titles, alternative titles, synopses,
  user names and proper names (studios, producers) are content and are not translated.
- Quantities use plural resources (Russian needs `one`, `few`, `many`, `other`). Sentences are never concatenated.
- Dates and times follow the locale and the device's 12/24-hour setting, in the device's time zone.
- **An air time** is written as "today 17:30", "tomorrow 17:30", the short weekday with the time within the next
  six days ("Sat 17:30", «сб 17:30»), and the date with the time beyond that ("14 Nov, 17:30"). It never has both a
  weekday and a date. An air time always follows other text, so "today", "tomorrow" and their Russian forms are in
  lower case. A next episode is written in one form everywhere, the list row and the airing block alike:
  "Episode 15 · Sat 17:30", «Серия 15 · сб 17:30», "Episode 15 · today 17:30". A premiere whose time is known is
  written the same way: "Premieres Sat 17:30", «Премьера: сб 17:30».
- **A date from MAL** is written by its precision: a full date as day and short month ("4 Jan", «4 янв.»), with the
  year added on the title page ("4 Jan 2027") and not in the list row; a year and month as "January 2027"
  («в январе 2027 г.» after «Премьера»); a year alone as "2027" («в 2027 г.» after «Премьера»).
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

Screens are rendered on the JVM by Roborazzi on Robolectric, which draws Compose with the real Android rendering
code. Layout review works from these images.

- The stateless screen is rendered with fixture states. No view model, network or database is involved, times are
  written against the fixtures' fixed clock in the fixtures' own time zone, never the machine's, and remote images
  are flat grey blocks in a colour no surface role uses, with a MAL poster's proportions where only one side is
  set. Two renders of one commit on one machine are byte-identical.
- Every wall clock the app's code, `:core` or the date picker can read gives the fixtures' moment: `System`,
  `java.time`'s `now()` and system clocks, `Calendar` and Android's `DateUtils`. Three read the machine's clock, and
  a test fails when the app's code uses one: the `Date()` and `GregorianCalendar()` constructors and Kotlin's
  `Clock.System`.
- The status bar and the gesture bar are drawn, with their insets, so content hidden under one is visible in
  review. Both are 24 dp; the gesture bar is a handle without a scrim, as on a phone with gesture navigation, and
  a modal bottom sheet draws its container behind it. Robolectric renders at API 35, the highest it runs on JDK 17.
- A case can declare the keyboard open. Every window then gets a 300 dp keyboard's insets (the gesture bar
  included), and the image shows a flat block with the handle in its place.
- A case can declare its own window size, such as the shell's wide window; it then replaces the variant's screen
  size, and the variant keeps its language, theme and font scale.
- Each screen has one registry file, `<Screen>Screenshots`, whose class name gives the screen's directory. It
  declares its **cases**: content, loading, empty and error, plus the cases its roadmap task names. A screen that
  has no loading, empty or error state says so with a reason of at least two words, which the render writes to
  `<screen>/NOT_SHOWN.txt`; a placeholder such as "TODO" or "n/a" fails. Only `sample` and
  `gallery` are exempt. A **gallery** case renders every component of section 8.4 in all its states.
- An ordinary test run renders nothing, but checks every registry's structure; only a record run renders.

**Variants.** Every case is rendered in the five standard variants. The content case of each screen gets two more.

| Variant | Language | Theme | Screen | Font scale | Rendered for |
|---|---|---|---|---|---|
| `en-light` | English | Light | 411 × 891 dp | 1.0 | every case |
| `en-dark` | English | Dark | same | 1.0 | every case |
| `ru-light` | Russian | Light | same | 1.0 | every case |
| `ru-dark` | Russian | Dark | same | 1.0 | every case |
| `ru-stress` | Russian | Light | 360 × 640 dp | 1.3 | every case |
| `ru-large` | Russian | Light | 411 × 891 dp | 2.0 | content case |
| `en-alt` | English | Light, second seed colour | 411 × 891 dp | 1.0 | content case |

**Captures.** In `app/build/outputs/roborazzi/<screen>/`, for each case and variant:

- `<case>__<variant>.png`: the viewport, as the user first sees it.
- `<case>__<variant>__full.png`: the whole scrollable content at full height, whenever it is taller than the
  viewport. Without it the lower half of the title page would never be reviewed. With a sheet or dialog open, it
  is made from the sheet's or dialog's own content, and there is none when that content does not scroll. A sheet
  that opens half expanded is shown expanded, while the viewport image shows how it opens. If a sheet's or
  dialog's content still reaches below the captured window, the render fails instead of writing a cut image. The
  window is made taller to take it, so content that is centred, weighted or sized to the screen moves: positions
  are judged in the viewport image.
- `<case>__<variant>__strip-<n>.png`, for the content case: the `n`th horizontal strip from the top, stitched
  from its pages so that every card shows. Magenta fills what a page did not cover because the strip was lower
  there.
- `<case>__bounds.png`, for the content case in `en-light`: the viewport with the bounds of the components
  outlined, for checking alignment.

`__full` images are written for: **all variants**.

For every case, sheets and dialogs included, an automatic accessibility check fails the test on a touch target
under 48 dp in any variant, and on an unlabeled control in `en-light`. It runs where everything is composed: on the
full-height render, and on every page of every horizontal strip. A touch target is the laid-out size of a clickable, toggleable or
selectable element, not the larger area Compose accepts touches in. The 32 dp wide drag handle of the stock modal
bottom sheet is the one exemption. A link inside a text is a touch target too, and is reported by its text. The
check's result for a screen is written next to its images as `ACCESSIBILITY.txt`.

**What an image cannot show.** Robolectric renders at density 2.0, where phones run at 2.625 or 3, so hairlines
and sub-dp rounding differ. The status bar is always 24 dp, so a top padding written as 24 dp instead of the inset
passes. The keyboard is a block of fixed height, without keys or suggestions. Text scales the way Compose scales
it at font scales above 1.03, non-linearly, on every Android version.

**Looping animations.** The harness holds every infinite transition and infinite repeatable animation at one fixed
frame, where an indeterminate progress indicator shows a long arc and reads as a spinner, and fails the render when
more of them run than the progress indicators on screen account for. A loop written by hand (an animation restarted
in a `while` loop, a blink on `delay`) is not detected: it stops at its first frame.

**The render names its commit.** `app/build/outputs/roborazzi/RENDERED_AT` holds the hash of the rendered commit and
says "dirty" when the worktree had uncommitted changes. A render after any commit, including one that changes no
source file, runs again and names that commit; it never comes from the build cache. Every record run first deletes
all earlier images, so after rendering one screen only that screen's images exist, and `RENDERED_AT` is written
only when the run succeeds.

Screenshots are build output and are not committed, except the calibration set of the rubric; CI uploads the full
set. On a pull request CI renders GitHub's merge commit, so its `RENDERED_AT` names that commit. Fonts rasterise differently between operating systems, so images are compared only when rendered on the same
machine.

**The review loop.** A layout task is finished when it passes `docs/LAYOUT_RUBRIC.md` with both reviewers (its
section 4 is the rule). The author renders after the final commit, checks that `RENDERED_AT` names it, looks at
every image, and hands over the commit hash and the output directory.

## 12. Build, platform and distribution

- **`minSdk` 26** (Android 8.0): `java.time` without desugaring, notification channels and adaptive icons
  everywhere. **`targetSdk` 37** (Android 17). **`compileSdk` 37, minor API level 2**: current Compose and
  `androidx.core` require 37, and the build uses the SDK package `platforms;android-37.2`. JDK 17.
- The Android Gradle Plugin compiles Kotlin itself; the project pins a newer Kotlin than the plugin's default.
- **Checks.** ktlint with its default rules and two exceptions: the package-name rule is off, because it rejects
  the underscore in the base package, and composable functions are exempt from the function-naming rule. Android
  Lint with the translation checks of section 10 as errors; other warnings are not errors, because checks such as
  "newer version available" would fail the build whenever a library publishes a release.
- **The maintainer's machine is shared**, so the build keeps no process alive after a job (no Gradle daemon) and
  runs one test JVM at a time; the memory settings change only with a measurement. `CLAUDE.md` has the queue rules.
- **CI** (GitHub Actions, repository `kauri-off/kiroku`) runs on every pull request and on pushes to `main`: the
  format check, Lint, the tests, the screenshots, a debug build and the self-test of the queue wrappers, keeping
  the screenshots and the APK as artifacts, and a check that the pull request title is a conventional commit. Its
  debug build has no client id, so the APK it keeps runs in demo mode.
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
