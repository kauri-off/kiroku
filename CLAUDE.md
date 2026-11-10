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

Run these through `tools/gw` on the maintainer's machine (next section). Elsewhere, run `./gradlew` with the same
arguments, without `--kind …`. `tools/gw tasks` lists the tasks.

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

This machine is shared with another project. Every Gradle run, emulator run and `adb` call goes through the shared
job queue by way of `tools/gw` and `tools/emu`, including short ones; run them from inside your worktree. Never call
`./gradlew`, `gradle`, `emulator` or `adb` directly: `adb` is used only inside `tools/emu run`, where it reaches that
job's emulator and nothing else. Never touch the other project's virtual devices (`~/.android/avd*`), snapshots or
jobs.

- `tools/gw [--kind test|check] <gradle arguments>` runs Gradle as a job. The default kind is `test` (one module,
  filtered tests, one screen); use `check` for whole-project runs. Gradle sees no device: a task that needs one
  (`connected…`, `install…`) runs only as `tools/emu run tools/gw <task>`. Gradle managed devices are not used:
  they start emulators outside `tools/emu`.
- `tools/emu run <command> [args…]` (several commands: `tools/emu run sh -c '…'`) boots Kiroku's own API 36
  emulator from a clean state in the slot the queue assigns, runs the command in the worktree with `adb` on the
  `PATH`, then stops the command and the emulator. A run takes a minute or more, so expect exit 75 at first. The
  emulator's log is `build/emu/emulator-<port>.log`. The queue counts an emu job like a `check` job, but one that
  runs Gradle needs about 3 GiB more for the emulator: build first (`tools/gw assembleDebug
  assembleDebugAndroidTest`), then run only the device task, which Gradle runs there with two workers.
- `tools/emu smoke <apk>` installs and launches the APK; it passes when the app reaches the foreground without a
  crash or an ANR, and writes two screenshots, the logcat and the crash buffer to `build/emu/smoke/`.
- `tools/emu ps` lists running Kiroku emulators. `tools/selftest` tests both wrappers against a fake queue and SDK.

A call waits up to 100 seconds. When the job has ended, it prints the job's last 60 lines and the path of its full
log, and exits with:

| Exit | Meaning | What to do |
|---|---|---|
| 0 | Passed | |
| 1, other | Gradle's or the command's own code. If it was 2, 69, 75, 76 or 125, the wrapper reports 1 and prints "… exited <n>, reported as 1" | Read the output |
| 2 | The wrapper refused the call (arguments, kind, unknown job id, worktree); nothing ran | Fix the call |
| 69 | `tools/emu` only: no emulator could be started, or it died; the reason is printed | Not your command's result: run it once more; if it repeats, stop and report the reason |
| 75 | The job is queued or running, or the queue could not report on it | Run the printed `--wait <id>` command again, as often as needed; do not start the job again |
| 76 | The job is stalled | If the output explains the wait, wait again; otherwise stop it with `/home/anon/Projects/th_08/tools/q kill <id>` |
| 125 | The job was stopped (`q kill`, or its worktree was removed) | Start it again if you still need the result |

Do not start a Gradle daemon, do not change the memory settings in `gradle.properties` without a measurement, and do
not use the queue kinds `full`, `batch` or `long`.

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
- Tick the task's checkbox in `ROADMAP.md` in the same branch.

## Code conventions

- Default to no code comments. Where one is needed, one short line at most.
- No user-visible string outside `app/src/main/res`, except the fallback for a value from MAL the app does not
  know (its code written as words, or a genre's MAL name). Every string exists in English and Russian, in the feature's
  own strings file, with the glossary's terms; quantities use plurals. Values from MAL that read as interface text
  (status, type, season, genre, relation) are translated too.
- Screens are stateless: `XScreen(state, onEvent)` with fixture states. Only `XRoute` touches a view model or
  navigation. Each screen has its own screenshot registry file.
- User interface code uses stock Material 3 components, theme colour roles, the `Spacing` tokens and the components
  listed in ARCHITECTURE section 8.4. No literal colours or dp values, no custom controls, no toasts.
- Motion follows ARCHITECTURE section 8.5: sparse, from the theme's motion tokens only, with the tests and the
  frame strip it asks for.
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
