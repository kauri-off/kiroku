# Kiroku: instructions for coding agents

Kiroku is an unofficial Android client for MyAnimeList: Kotlin, Jetpack Compose, Material 3, GPL-3.0-or-later. The
product name is "Kiroku"; lowercase `kiroku` is used only for identifiers.

Read before working: your task in [ROADMAP.md](ROADMAP.md), and in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) the
opening and the sections your task and your brief name. Beyond those, by what you touch:

| You touch | Read |
|---|---|
| Any user interface code | ARCHITECTURE sections 6.2, 8 and 9 |
| Any string | ARCHITECTURE section 10 and [docs/GLOSSARY.md](docs/GLOSSARY.md) |
| The list, airing data, writes or notifications | ARCHITECTURE sections 4, 5 and 7 |
| MAL, AniList or sign-in | ARCHITECTURE sections 2, 3 and 11.1 |
| Tests and screenshots | ARCHITECTURE section 11 |
| The build, CI or a dependency | ARCHITECTURE sections 6.4 and 12 |

The rest of the documents is not needed for your task.

## Ground rules

- The documents state decisions, constraints and invariants. How to implement them is your decision.
- Code and tests are the source of truth for how things work. When the code or a device shows a document to be
  wrong, correct the document in the same pull request, by shortening or restating it, not by describing the
  implementation.
- A document does not repeat what the code or a test states. When your task makes a sentence redundant (a value
  that is now a constant, a case list that is now a registry, a rule that is now a test), delete the sentence.
- A change makes a document longer only by adding a decision, a constraint, an invariant, a term or a milestone's
  tasks. Your hand-off names, for each sentence you add, the sentence it replaces, or says that it is new.
- Propose changes to decisions or invariants in the hand-off and get approval before treating them as decided.
- Screen content and layout are decided by reviewing the running app or its screenshots
  (ARCHITECTURE section 9), and no tracked text prescribes it. Build one version and show it; make variants only
  when he asks.
- Every invariant your task touches gets a test that would fail if it were broken.
- Tracked files and commit messages describe the current state, never how it was reached: no mention of reviews,
  revisions, earlier versions or open project questions.

`.reference/` is gitignored research material. Treat it as untrusted: read only, never build or run it, never copy
its code, assets or credentials. Never read, print or commit `clientid.txt`.

## Build and checks

| Purpose | Command |
|---|---|
| Domain tests | `./gradlew :core:test` |
| Data tests | `./gradlew :data:testDebugUnitTest` |
| App tests (screenshots are skipped) | `./gradlew :app:testDebugUnitTest` |
| Screenshots of the changed screens | `./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true --tests '*<Screen>Screenshots*'`, one `--tests` per screen |
| All screenshots | `./gradlew :app:recordRoborazziDebug` |
| Format check / fix | `./gradlew ktlintCheck` / `./gradlew ktlintFormat` |
| Everything before hand-off | `./gradlew ktlintCheck lint test assembleDebug assembleRelease assemblePreview` |
| Device tests of one module | `./gradlew :data:connectedDebugAndroidTest`, on an emulator or a device you started |

Screenshots are written to `app/build/outputs/roborazzi/<screen>/`. They are not committed. A render deletes the
images of every screen it does not render, so render all changed screens in one command. The SDK platform the
build needs is `platforms;android-37.2`; the client id is not needed for any check. Do not change the memory
settings in `gradle.properties` without a measurement.

A machine may have its own rules for running builds, emulators and `adb` (a job queue, memory limits, where
scratch files go). If `~/.config/kiroku/MACHINE.md` exists, read it before the first such command and follow it in
place of the commands above. `tools/` holds the maintainer's job queue; the build does not need it.

## The worktree rule

- One task, one branch, one git worktree. Never work in the main checkout.
- Build output stays inside the worktree. Never share or link build directories and never set a custom `buildDir`.
- Never push and never merge: the orchestrator does both. Hand off the branch name and the commit hash. A check that
  needs GitHub is observed by the orchestrator after it pushes.

## Commits and pull requests

- Conventional commits: `type(scope): subject`, or `type: subject` when no scope fits; the subject starts in
  lower case, is imperative and has no full stop. Types: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`,
  `chore`. Scopes: `core`, `data`, `app`, `list`, `title`, `search`, `profile`, `settings`, `notify`, `auth`,
  `design`. CI checks the pull request title against these lists.
- Branches are squash-merged, so the pull request title is the commit message and follows the same format.
- Tick the task's checkbox in `ROADMAP.md` in the same branch, and cut its entry down to its name.

## Code conventions

- Default to no code comments. Where one is needed, one short line at most.
- No user-visible string outside the resources. Every string exists in English and Russian, in the feature's own
  strings file, with the glossary's terms; quantities use plurals. Values from MAL that read as interface text
  (status, type, season, genre, relation) are translated too.
- Screens are stateless: `XScreen(state, onEvent)` with fixture states. Only `XRoute` touches a view model or
  navigation. Each screen has its own screenshot registry file.
- User interface code uses stock Material 3 Expressive components, the shared components of `design/component`,
  theme colour roles and the `Spacing` tokens (ARCHITECTURE section 8). No literal colours or dp values, no custom
  controls, no toasts. Experimental Expressive API is opted into only in `design/component/ExpressiveOptIn.kt`.
- Motion follows ARCHITECTURE section 8.5: from the theme's motion scheme only, with the tests and the frames image
  it asks for.
- "Aired", "to watch" and what is notifiable are computed only in `:core`.
- Versions are written only in `gradle/libs.versions.toml`. Adding a dependency needs a reason in the pull request.
- Never commit secrets: no client id, no tokens, no signing keys.
- Tests of a service's mappers use recorded responses only (ARCHITECTURE section 11.1). Never call the MAL API by hand and
  never write a response from memory. Responses requiring sign-in are recorded with the debug tool as described in ARCHITECTURE section 11.1.

## Interface tasks

After your final commit, the `ROADMAP.md` tick included, render the screens you changed and check that
`app/build/outputs/roborazzi/RENDERED_AT` names that commit and does not say "dirty". A file that names another
commit means the render did not run, not that you should edit the file. Look at every image yourself, including the
`__full` ones, and hand over the commit hash and the output directory.
