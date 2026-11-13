# Kiroku: instructions for coding agents

Kiroku is an unofficial Android client for MyAnimeList: Kotlin, Jetpack Compose, Material 3, GPL-3.0-or-later. The
product name is "Kiroku"; lowercase `kiroku` is used only for identifiers.

Read before working: your task in [ROADMAP.md](ROADMAP.md), and in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) the
opening and the sections your task and your brief name. Beyond those, by what you touch:

| You touch | Read |
|---|---|
| Any user interface code | ARCHITECTURE sections 6.2 and 8 |
| A screen's layout | Its budget in ARCHITECTURE section 9, and [docs/LAYOUT_RUBRIC.md](docs/LAYOUT_RUBRIC.md) |
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
- Decisions and invariants are the owner's. If you need to depart from one, do not do it silently: stop and report.
  A change to a budget, an invariant or a decision is the owner's too: propose it in the hand-off, do not write it
  into the document as decided.
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
| Screenshots of the changed screens | `tools/gw :app:testDebugUnitTest -Proborazzi.test.record=true --tests '*<Screen>Screenshots*'`, one `--tests` per screen |
| All screenshots | `tools/gw --kind check :app:recordRoborazziDebug` |
| Format check / fix | `tools/gw ktlintCheck` / `tools/gw ktlintFormat` |
| Everything before hand-off | `tools/gw --kind check ktlintCheck lint test assembleDebug` |

Screenshots are written to `app/build/outputs/roborazzi/<screen>/`. They are not committed. A render deletes the
images of every screen it does not render, so render all changed screens in one command. The SDK platform the
build needs is `platforms;android-37.2`; the client id is not needed for any check.

## The queue rule (the maintainer's machine)

Many agents build here at once. Every Gradle run, emulator run and `adb` call goes through Kiroku's job queue,
`tools/q`, by way of `tools/gw` and `tools/emu`, including short ones; run them from inside your worktree.
Never call `./gradlew`, `gradle`, `emulator` or `adb` directly: `adb` is used only inside `tools/emu run`, where it
reaches that job's emulator and nothing else. Never touch a virtual device outside `~/.android/kiroku`.

- `tools/gw [--kind test|check] <gradle arguments>` runs Gradle as a job. The default kind is `test` (one module,
  filtered tests, a few screens); use `check` for whole-project runs: the kind sets the memory the queue reserves.
  Gradle sees no device: a task that needs one (`connected…`, `install…`) runs only as
  `tools/emu run tools/gw <task>`. Gradle managed devices are not used: they start emulators outside `tools/emu`.
- `tools/emu run <command> [args…]` (several commands: `tools/emu run sh -c '…'`) boots Kiroku's own API 36
  emulator from a clean state in the slot the queue assigns, runs the command in the worktree with `adb` on the
  `PATH`, then stops the command and the emulator. A run takes a minute or more, so expect exit 75 at first. The
  emulator's log is `build/emu/emulator-<port>.log`. The emulator takes most of the job's memory: build first
  (`tools/gw assembleDebug assembleDebugAndroidTest`), then run only the device task, which Gradle runs there
  with two workers.
- `tools/emu smoke <apk>` installs and launches the APK; it passes when the app reaches the foreground without a
  crash or an ANR, and writes two screenshots, the logcat and the crash buffer to `build/emu/smoke/`.
- `tools/emu ps` lists running Kiroku emulators, `tools/q` the jobs and what each queued one waits for.
  `tools/selftest` tests the queue and both wrappers with fake jobs.

A job's temporary files go to the directory the queue gives it (`$TMPDIR`, on disk, removed when the job ends) and
never to `/tmp`, which is memory here.

A call waits up to 100 seconds. When the job has ended, it prints the job's last 60 lines, the path of its full
log and its memory peak, and exits with:

| Exit | Meaning | What to do |
|---|---|---|
| 0 | Passed | |
| 1, other | Gradle's or the command's own code. If it was 2, 69, 75, 76 or 125, the wrapper reports 1 and prints "… exited <n>, reported as 1" | Read the output |
| 2 | The call was refused (arguments, kind, job id, worktree, branch behind `main`, main checkout behind it); nothing ran | Fix what the message names, or try again in a minute when it says so |
| 69 | `tools/emu` only: no emulator could be started, or it died; the reason is printed | Not your command's result: run it once more; if it repeats, stop and report the reason |
| 75 | The job is queued or running, or the queue could not report on it | Run the printed `--wait <id>` command again, as often as needed; do not start the job again |
| 76 | The job is stalled | If the output explains the wait, wait again; otherwise stop it with `tools/q kill <id>` |
| 125 | The job was stopped (`tools/q kill`, its worktree was removed, or the system killed it for lack of memory) | Start it again if you still need the result |

Do not start a Gradle daemon, and do not change the memory settings in `gradle.properties` or the limits in
`tools/q.conf` without a measurement.

`/tmp` is a RAM disk here: bulky scratch (recording frames, extracted files) goes under the worktree's `build/` and
is deleted before the hand-off.

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
- User interface code uses stock Material 3 Expressive components in the choices of ARCHITECTURE section 8.4, theme
  colour roles, the `Spacing` tokens and the components listed there. No literal colours or dp values, no custom
  controls, no toasts. Experimental Expressive API is opted into only in `design/component/ExpressiveOptIn.kt`.
- Motion follows ARCHITECTURE section 8.5: from the theme's motion scheme only, with the tests and the frames image
  it asks for.
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
