# Kiroku privacy statement

Kiroku connects directly to the services described below. It has no server of its own, analytics, or remote crash
reporting. [App wiring][KirokuApplication.kt] · [Dependencies][gradle/libs.versions.toml] · [Local error logging][Reports.kt]

## Network requests

- **MyAnimeList API (`api.myanimelist.net`).** Requests can include search text, title IDs, ranking or season
  parameters, page parameters, the adult-content flag for search, browse or recommendations, and the username
  of a public list you open. Live Search sends nonempty text after a pause in typing, even without submitting. Kiroku can also sync your list with MAL and refresh your access
  token in the background. Public requests use the build's MAL
  client ID. Reads of your own list and profile and writes to your list use a bearer access token. List changes
  send the title ID and updated fields such as status, score, watched episodes, or dates. Removing an entry sends
  its title ID. [API host][MalHosts.kt] · [Client ID header][SendRules.kt] · [Search and browse][MalSearch.kt] ·
  [Live search][SearchViewModel.kt] · [Background sync][PeriodicWork.kt] · [Lists][MalLists.kt] · [Profile][MalProfiles.kt] · [List writes][MalRemoteList.kt]
- **MyAnimeList sign-in (`myanimelist.net`).** Your browser opens MAL's OAuth page with the client ID, redirect URI,
  state and plain PKCE challenge. Kiroku sends the client ID and either the authorization code, verifier and
  redirect URI or the refresh token to MAL's token endpoint. Opening a title on MAL gives its page URL to Android's link
  handler. Sharing passes the same URL to the app you select in Android's chooser. [Authorization][MalSignIn.kt] · [Token endpoint][SessionOverHttp.kt] · [Refresh][MalSession.kt] ·
  [Title link][TitleRoute.kt]
- **AniList (`graphql.anilist.co`).** For episode schedules Kiroku sends MAL IDs of tracked, unfinished titles
  on your Watching, Planned and On hold lists, including in background schedule refreshes. Opening a title that
  MAL has yet to mark Finished can also send its ID, even if it is outside your list. These lookups require an
  existing MAL session, including an expired session. AniList receives the IDs, GraphQL query and pagination
  fields without MAL account credentials. [Schedule requests][AniListSchedule.kt] · [Schedule selection][AiringEngine.kt] ·
  [Session check][LiveAiring.kt] · [AniList client][AniListAiring.kt]
- **Tenrai (`api.tenrai.org`).** To fetch a title's score distribution, Kiroku sends its MAL title ID. Tenrai receives no
  MAL account credentials. [Score screen][SubScreenViewModels.kt] · [Statistics URL][TenraiStatistics.kt] ·
  [Tenrai client][TenraiScores.kt]
- **Image hosts.** Kiroku requests posters and your profile avatar from HTTPS URLs supplied by MyAnimeList.
  Image hosts receive the requested URL and Kiroku's user agent. Image requests omit the account access token.
  The MAL client-ID header is restricted to the MAL API origin. [Image loader][Images.kt] ·
  [Title data][MalTitles.kt] · [Profile data][MalProfiles.kt]

Services and image hosts can see the connection's IP address. Requests from Kiroku's shared HTTP client
identify the app and version in the `User-Agent` header. [HTTP client][MalHttp.kt]

## Data on your device

- The app's database keeps title details, related titles and recommendations, your anime list and profile
  (including your name, avatar URL, join date and statistics), tracked airing schedules, and notification tracking
  and history. [Database][KirokuDatabase.kt] · [Profile fields][ProfileRows.kt]
- App-private files store title language, the adult-content preference, list sort choices, recently opened profile
  names, notification preferences, and retry timers. While sign-in is pending, a separate private file holds the
  OAuth state, verifier and start time. A matching callback consumes the pending sign-in state. Its ten-minute acceptance window does not schedule
  file deletion. Android Keystore protects the key used to encrypt access and refresh tokens. A queued
  **Mark watched** notification action is stored locally. [Preferences][StoredPreferences.kt] ·
  [Service stores][LiveGraph.kt] · [Pending sign-in][PendingSignIn.kt] · [Callback][MalSignIn.kt] · [Token file][TokenStore.kt] ·
  [Encryption][TokenCipher.kt] · [Pending actions][MarkWatchedWork.kt]
- Search and browse results, other users' fetched public lists and computed statistics, and schedules fetched
  just for a title page are held in memory. Score distributions are cached for the life of the app process.
  Android's saved screen state can restore the current search text and recent searches after the app process
  is recreated. Images are cached in memory and on disk. [Search input][SearchViewModel.kt] ·
  [Recent searches][SearchRoute.kt] · [Search and browse][MalSearch.kt] · [Public lists][LiveUser.kt] ·
  [Title schedules][AiringEngine.kt] · [Score cache][ScoreDistribution.kt] · [Image cache][Images.kt]
- Kiroku disables Android backup and excludes its app data from device transfer. [Manifest][app/src/main/AndroidManifest.xml] ·
  [Extraction rules][data_extraction_rules.xml]

## Sign-out

Debug and Preview offer **Sign out** in the debug menu. It clears the local session and tokens, resets
navigation, cancels queued **Mark watched** jobs and clears account list, title and profile data, tracked
airing data, notification tracking and history, cached public lists, list sorts and recently opened profile
names. Title language, adult-content and notification preferences stay. If clearing tracked airing data fails,
the app retries when the store first opens while signed out in a later process. Sign-out retires that sign-in's poster and avatar cache and cancels its
image requests. Files are removed in the background after outstanding image work ends. If deletion fails,
remaining files are kept out of use and removal is retried when that source's image cache starts in a later
process. The app attempts to remove the old shared image cache at startup and retries on later starts.
Signed-out browsing uses its own image cache. If saving sign-out fails, a restart can restore the previous
session. The separately recorded retirement still keeps that session's old image cache out of use. If both
writes fail, the old cache can remain readable after restart. In-memory score
distributions and title-page schedules can last until the process ends. [Sign-out action][SignOutSection.kt] ·
[Cleanup][LiveGraph.kt] · [List store][RoomLocalList.kt] · [Preferences][StoredPreferences.kt] ·
[Navigation][SignOutTurns.kt] · [Image cache][Images.kt] · [Score cache][ScoreDistribution.kt] ·
[Title schedules][AiringEngine.kt]

In-app sign-out leaves pending browser sign-in state in place. A matching callback within the acceptance window
can still complete sign-in. [Pending browser sign-in][MalSignIn.kt]

In Release, use **Clear storage** for Kiroku in Android settings to remove local app data. This leaves
your MyAnimeList account and list on MyAnimeList. [Release wiring][DebugTools.kt] · [MAL list writes][MalRemoteList.kt]

## Diagnostics and test data

Release can log exception types and stack frames locally. Debug and Preview also keep bounded exception reports
and notification-run diagnostics on the device, and log request metadata and sign-in failure reasons locally.
Request logs omit headers, bodies and query values. URL paths can include title IDs or public-list usernames.
Notification run diagnostics can include titles and episode numbers from your list. Sign-out clears the prior
notification run log. Exception reports and Android log entries remain subject to their own retention.
[Release wiring][app/src/release/kotlin/io/github/kauri_off/kiroku/wiring/GraphFactory.kt] · [Error logging][Reports.kt] ·
[Debug reports][ReportLog.kt] · [Notification run log][RunLog.kt] · [Request logging][RequestLog.kt] ·
[Sign-in diagnostics][LiveSignIn.kt]

Debug and Preview have an opt-in recorder for signed-in MAL requests. **Check title** sends the chosen title
ID to MAL before recording is confirmed. After confirmation, the recorder reads your profile and list, makes
test changes to a title absent from your list, and attempts to remove that entry. If a run leaves the entry
behind, the recorder can retry removal for the same account when it starts again or when you select
**Remove now**. Sign-out leaves the private recovery journal and recorded or staged responses on the device.
Saved responses can include real profile and list data. The recorder removes the access token and client ID
from saved response bodies. The recovery journal stays in app-private internal storage. Recorded and staged
responses go to app-specific external storage, or the app's internal files directory if external storage is
unavailable. [Recorder UI][RecorderSection.kt] ·
[Recovery and saved requests][SignedInRecorder.kt] ·
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

[SearchViewModel.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/search/SearchViewModel.kt
[SearchRoute.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/search/SearchRoute.kt
[PeriodicWork.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/notify/PeriodicWork.kt
[LiveAiring.kt]: app/src/main/kotlin/io/github/kauri_off/kiroku/wiring/LiveAiring.kt
[SignOutSection.kt]: app/src/debug/kotlin/io/github/kauri_off/kiroku/signout/SignOutSection.kt
[DebugTools.kt]: app/src/release/kotlin/io/github/kauri_off/kiroku/wiring/DebugTools.kt
