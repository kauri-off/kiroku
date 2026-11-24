# Kiroku privacy statement

Kiroku connects directly to the services described below. It has no server of its own, analytics, or remote crash
reporting. [App wiring][KirokuApplication.kt] · [Dependencies][gradle/libs.versions.toml] · [Local error logging][Reports.kt]

## Network requests

- **MyAnimeList API (`api.myanimelist.net`).** Requests can include search text, title IDs, ranking or season
  parameters, page parameters, and the username of a public list you open. Public requests use the build's MAL
  client ID. Reads of your own list and profile and writes to your list use a bearer access token. List changes
  send the title ID and updated fields such as status, score, watched episodes, or dates. Removing an entry sends
  its title ID. [API host][MalHosts.kt] · [Client ID header][SendRules.kt] · [Search and browse][MalSearch.kt] ·
  [Lists][MalLists.kt] · [Profile][MalProfiles.kt] · [List writes][MalRemoteList.kt]
- **MyAnimeList sign-in (`myanimelist.net`).** Your browser opens MAL's OAuth page with the client ID, redirect URI,
  state and plain PKCE challenge. Kiroku sends the client ID and either the authorization code, verifier and
  redirect URI or the refresh token to MAL's token endpoint. Opening a title on MAL hands its page URL to your
  browser. [Authorization][MalSignIn.kt] · [Token endpoint][SessionOverHttp.kt] · [Refresh][MalSession.kt] ·
  [Title link][TitleRoute.kt]
- **AniList (`graphql.anilist.co`).** For episode schedules Kiroku sends MAL IDs of tracked, unfinished titles
  and of airing titles whose pages you open, with the GraphQL query and pagination fields. AniList receives no MAL
  account credentials. [Schedule requests][AniListSchedule.kt] · [Schedule selection][AiringEngine.kt] ·
  [AniList client][AniListAiring.kt]
- **Tenrai (`api.tenrai.org`).** Opening a title's score distribution sends its MAL title ID. Tenrai receives no
  MAL account credentials. [Score screen][SubScreenViewModels.kt] · [Statistics URL][TenraiStatistics.kt] ·
  [Tenrai client][TenraiScores.kt]
- **Image hosts.** Kiroku requests posters and your profile avatar from HTTPS URLs supplied by MyAnimeList.
  The hosts receive the image requests through Kiroku's shared HTTP client. [Image loader][Images.kt] ·
  [Title data][MalTitles.kt] · [Profile data][MalProfiles.kt]

Services and image hosts can see the connection's IP address. Requests from Kiroku's shared HTTP client
identify the app and version in the `User-Agent` header. [HTTP client][MalHttp.kt]

## Data on your device

- The app's database keeps title details, related titles and recommendations, your anime list and profile
  (including your name, avatar URL, join date and statistics), tracked airing schedules, and notification tracking
  and history. [Database][KirokuDatabase.kt] · [Profile fields][ProfileRows.kt]
- App-private files store list sort choices, recently opened profile names, notification preferences, and retry
  timers. While sign-in is pending, a separate private file holds the OAuth state, verifier and start time.
  The token file stores a local sign-in ID alongside the token data, which is encrypted with a key in Android
  Keystore. A pending **Mark watched** notification action is stored as a WorkManager job. [Preferences][StoredPreferences.kt] ·
  [Service stores][LiveGraph.kt] · [Pending sign-in][PendingSignIn.kt] · [Token file][TokenStore.kt] ·
  [Encryption][TokenCipher.kt] · [Pending actions][MarkWatchedWork.kt]
- Search and browse results, other users' fetched public lists and computed statistics, and schedules fetched
  just for a title page are held in memory. Score distributions are cached for the life of the app process.
  Coil caches images in memory and on disk. [Search and browse][MalSearch.kt] · [Public lists][LiveUser.kt] ·
  [Title schedules][AiringEngine.kt] · [Score cache][ScoreDistribution.kt] · [Image cache][Images.kt]
- Kiroku disables Android backup and excludes its app data from device transfer. [Manifest][app/src/main/AndroidManifest.xml] ·
  [Extraction rules][data_extraction_rules.xml]

## Sign-out

Signing out clears the local session and its tokens and returns the app to signed-out navigation. Sign-out
also cancels pending **Mark watched** jobs and clears account list, title and profile data, tracked airing
data, notification tracking and history, cached public lists, list sorts and recently opened profile names.
Notification preferences stay.
Coil's memory and disk image caches are outside sign-out cleanup, so posters and avatars can remain cached.
In-memory score distributions can last until the process ends. [Cleanup][LiveGraph.kt] · [List store][RoomLocalList.kt] ·
[Preferences][StoredPreferences.kt] · [Navigation][SignOutTurns.kt] · [Image loader][KirokuApplication.kt] ·
[Score cache][ScoreDistribution.kt]

## Diagnostics and test data

Release can log exception types and stack frames locally. Debug and Preview also keep bounded exception reports
and notification-run diagnostics on the device, and log request metadata and sign-in failure reasons locally.
Request logs omit headers, bodies and query values. URL paths can include title IDs or public-list usernames.
[Release wiring][app/src/release/kotlin/io/github/kauri_off/kiroku/wiring/GraphFactory.kt] · [Error logging][Reports.kt] ·
[Debug reports][ReportLog.kt] · [Notification run log][RunLog.kt] · [Request logging][RequestLog.kt] ·
[Sign-in diagnostics][LiveSignIn.kt]

Debug and Preview have an opt-in recorder for signed-in MAL requests. After confirmation, it reads your profile
and list, makes test changes to a title absent from your list, and attempts to remove that entry again. It keeps
a private recovery journal and saves MAL responses, which can contain profile and list data, in app-specific
external storage or the app's internal files directory if external storage is unavailable. The recorder writes
these files locally. [Recorder UI][RecorderSection.kt] · [Recorded requests][SignedInRecorder.kt] ·
[Output directories][app/src/debug/kotlin/io/github/kauri_off/kiroku/wiring/LiveRecorder.kt]

Demo uses in-memory fixtures, and Sandbox answers its service requests on the device. [Debug source wiring][app/src/debug/kotlin/io/github/kauri_off/kiroku/wiring/GraphFactory.kt] ·
[Sandbox][SandboxGraph.kt]

[KirokuApplication.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/KirokuApplication.kt
[gradle/libs.versions.toml]: gradle/libs.versions.toml
[Reports.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/wiring/Reports.kt
[MalHosts.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/network/MalHosts.kt
[SendRules.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/network/SendRules.kt
[MalSearch.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/anime/MalSearch.kt
[MalLists.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/list/MalLists.kt
[MalProfiles.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/profile/MalProfiles.kt
[MalRemoteList.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/list/MalRemoteList.kt
[MalSignIn.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/signin/MalSignIn.kt
[SessionOverHttp.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/session/SessionOverHttp.kt
[MalSession.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/session/MalSession.kt
[TitleRoute.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/title/TitleRoute.kt
[AniListSchedule.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/anilist/AniListSchedule.kt
[AiringEngine.kt]: core/src/main/kotlin/io/github/kauri_off/kiroku/core/airing/AiringEngine.kt
[SubScreenViewModels.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/titlesub/SubScreenViewModels.kt
[TenraiStatistics.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/tenrai/TenraiStatistics.kt
[Images.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/wiring/Images.kt
[MalTitles.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/anime/MalTitles.kt
[MalHttp.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/network/MalHttp.kt
[KirokuDatabase.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/db/KirokuDatabase.kt
[ProfileRows.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/db/ProfileRows.kt
[StoredPreferences.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/preferences/StoredPreferences.kt
[LiveGraph.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/wiring/LiveGraph.kt
[PendingSignIn.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/signin/PendingSignIn.kt
[TokenStore.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/session/TokenStore.kt
[TokenCipher.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/session/TokenCipher.kt
[MarkWatchedWork.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/notify/MarkWatchedWork.kt
[LiveUser.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/wiring/LiveUser.kt
[ScoreDistribution.kt]: core/src/main/kotlin/io/github/kauri_off/kiroku/core/score/ScoreDistribution.kt
[app/src/main/AndroidManifest.xml]: app/src/main/AndroidManifest.xml
[data_extraction_rules.xml]: app/src/main/res/xml/data_extraction_rules.xml
[RoomLocalList.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/db/RoomLocalList.kt
[SignOutTurns.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/shell/SignOutTurns.kt
[app/src/release/kotlin/io/github/kauri_off/kiroku/wiring/GraphFactory.kt]: app/src/release/kotlin/io/github/kauri_off/kiroku/wiring/GraphFactory.kt
[ReportLog.kt]: app/src/debug/kotlin/io/github/kauri_off/kiroku/reports/ReportLog.kt
[RunLog.kt]: app/src/debug/kotlin/io/github/kauri_off/kiroku/notificationlog/RunLog.kt
[RequestLog.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/network/RequestLog.kt
[LiveSignIn.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/wiring/LiveSignIn.kt
[RecorderSection.kt]: app/src/debug/kotlin/io/github/kauri_off/kiroku/recorder/RecorderSection.kt
[SignedInRecorder.kt]: data/src/debug/kotlin/io/github/kauri_off/kiroku/data/recorder/SignedInRecorder.kt
[app/src/debug/kotlin/io/github/kauri_off/kiroku/wiring/LiveRecorder.kt]: app/src/debug/kotlin/io/github/kauri_off/kiroku/wiring/LiveRecorder.kt
[app/src/debug/kotlin/io/github/kauri_off/kiroku/wiring/GraphFactory.kt]: app/src/debug/kotlin/io/github/kauri_off/kiroku/wiring/GraphFactory.kt
[SandboxGraph.kt]: app/src/debug/kotlin/io/github/kauri_off/kiroku/sandbox/SandboxGraph.kt
[AniListAiring.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/anilist/AniListAiring.kt
[TenraiScores.kt]: data/src/main/kotlin/io/github/kauri_off/kiroku/data/tenrai/TenraiScores.kt
