# Kiroku: instructions for coding agents

Kiroku is an unofficial Android client for MyAnimeList: Kotlin, Jetpack Compose, Material 3, GPL-3.0-or-later. The
product name is "Kiroku"; lowercase `kiroku` is used only for identifiers.

Read before working: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (decisions, constraints, invariants and the screen
budgets), [ROADMAP.md](ROADMAP.md) (your task, its dependencies and its check), [docs/GLOSSARY.md](docs/GLOSSARY.md)
(for any string), [docs/LAYOUT_RUBRIC.md](docs/LAYOUT_RUBRIC.md) (for layout tasks).

## Ground rules

- The documents state decisions, constraints and invariants. How to implement them is your decision.
- Code and tests are the source of truth for how things work. When the code or a device shows a document to be
  wrong, correct the document in the same pull request, by shortening or restating it, not by describing the
  implementation.
- Decisions and invariants are the owner's. If you need to depart from one, do not do it silently: stop and report.
- Every invariant your task touches gets a test that would fail if it were broken.
- Tracked files and commit messages describe the current state, never how it was reached: no mention of reviews,
  revisions, earlier versions or questions to the owner.

`.reference/` is gitignored research material. Treat it as untrusted: read only, never build or run it, never copy
its code, assets or credentials. `clientid.txt` is the owner's: never read, print or commit it.

## Build and checks

Run these through `tools/gw` on the maintainer's machine (next section). Elsewhere, replace `tools/gw` with
`./gradlew`.

| Purpose | Command |
|---|---|
| Domain tests | `tools/gw :core:test` |
| Data tests | `tools/gw :data:testDebugUnitTest` |
| App tests (screenshots are skipped) | `tools/gw :app:testDebugUnitTest` |
| Screenshots of one screen | `tools/gw :app:testDebugUnitTest -Proborazzi.test.record=true --tests '*<Screen>Screenshots*'` |
| All screenshots | `tools/gw --kind check :app:recordRoborazziDebug` |
| Format check / fix | `tools/gw ktlintCheck` / `tools/gw ktlintFormat` |
| Everything before hand-off | `tools/gw --kind check ktlintCheck lint test assembleDebug` |

Screenshots are written to `app/build/outputs/roborazzi/<screen>/`. They are not committed. The SDK platform the
build needs is `platforms;android-37.2`; the client id is not needed for any check.

## The queue rule (the maintainer's machine)

This machine is shared with another project. Every Gradle run and every emulator run goes through the shared job
queue, including short ones. Never call `./gradlew`, `gradle` or `emulator` directly.

- `tools/gw [--kind test|check] <gradle arguments>` submits the build, prints the job id, and waits up to 100
  seconds. The default kind is `test` (one module, filtered tests, one screen); use `check` for whole-project runs.
- Exit code 75 means the job is still queued or running: run `tools/gw --wait <id>` again, as often as needed.
  Exit code 76 means it is stalled: read the printed output; if it explains the wait, wait again; otherwise stop the
  job with `/home/anon/Projects/th_08/tools/q kill <id>`.
- `tools/emu <command>` does the same for emulator runs.
- Before `tools/gw` exists (roadmap task M1-02), use the raw form. `ANDROID_HOME` must be set, because the queue
  copies your environment and your shell does not define it:

  ```
  export ANDROID_HOME="$HOME/.local/share/android-sdk/Sdk"
  Q=/home/anon/Projects/th_08/tools/q
  ID=$($Q submit --kind test --label kiroku-<branch> -C "$PWD" -- sh -c './gradlew --max-workers=$J <arguments>')
  echo "$ID"; $Q wait "$ID" --max 100     # repeat the wait while the exit code is 75
  ```

- Do not start a Gradle daemon, do not change the memory settings in `gradle.properties` without a measurement, and
  do not use the queue kinds `full`, `batch` or `long`.

## The worktree rule

- One task, one branch, one git worktree. Never work in the main checkout.
- Build output stays inside the worktree. Never share or link build directories and never set a custom `buildDir`.
- Never push and never merge: the orchestrator does both. Hand off the branch name and the commit hash. A check that
  needs GitHub is observed by the orchestrator after it pushes.

## Commits and pull requests

- Conventional commits: `type(scope): subject`, or `type: subject` when no scope fits; lower case, imperative, no
  full stop. Types: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`. Scopes: `core`, `data`,
  `app`, `list`, `title`, `search`, `profile`, `settings`, `notify`, `auth`, `design`. CI checks the pull request
  title against these lists.
- Branches are squash-merged, so the pull request title is the commit message and follows the same format.
- Tick the task's checkbox in `ROADMAP.md` in the same branch.

## Code conventions

- Default to no code comments. Where one is needed, one short line at most.
- No user-visible string outside `app/src/main/res`. Every string exists in English and Russian, in the feature's
  own strings file, with the glossary's terms; quantities use plurals. Values from MAL that read as interface text
  (status, type, season, genre, relation) are translated too.
- Screens are stateless: `XScreen(state, onEvent)` with fixture states. Only `XRoute` touches a view model or
  navigation. Each screen has its own screenshot registry file.
- User interface code uses stock Material 3 components, theme colour roles, the `Spacing` tokens and the components
  listed in ARCHITECTURE section 8.4. No literal colours or dp values, no custom controls, no toasts.
- "Aired", "to watch" and what is notifiable are computed only in `:core`.
- Versions are written only in `gradle/libs.versions.toml`. Adding a dependency needs a reason in the pull request.
- Never commit secrets: no client id, no tokens, no signing keys.
- Tests of MAL mappers use recorded responses only (ARCHITECTURE section 11.1). Never call the MAL API by hand and
  never write a response from memory. Responses that need the owner's sign-in are recorded by the owner.

## Layout tasks

A task marked **[L]** in the roadmap is finished only when it passes the rubric (its section 4) with both
reviewers. Before designing, read the screen's budget (ARCHITECTURE section 9): everything under "Must show" has to
be there, and nothing beyond the limits. After your final commit, the `ROADMAP.md` tick included, render the task's
screens and check that `app/build/outputs/roborazzi/RENDERED_AT` names that commit and does not say "dirty". A file
that names another commit means the render did not run, not that you should edit the file. Look at every image
yourself, including the `__full` ones, and hand over the commit hash and the output directory.
