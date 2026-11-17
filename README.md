# GRBL Commander

GRBL Commander is a cross-platform desktop application for controlling CNC
machines that use the GRBL firmware.
It provides a user-friendly interface for sending G-code commands, monitoring
machine status, and managing jobs.

Focus is on providing a familiar UI to users of conventional CNC controllers,
like Fanuc, Haas, and Siemens on a touchscreen interface.
While it is not necessarily easy to use or intuitive for users who are not
familiar with CNC controllers, it is designed to be a powerful tool for those
who are.

## License and commercial use

GRBL Commander is **source-available** under the
[PolyForm Noncommercial License 1.0.0](LICENSE.md). Personal hobby use,
study, experimentation, modification, and noncommercial redistribution are
permitted under its terms. The license also explicitly permits use by
specified educational, charitable, research, public-sector and similar
organizations.

**Commercial use requires prior permission from the maintainer**, including
internal use in a commercial workshop even when the software itself is not
resold. Permission may be granted without a fee, especially to organizations
that support development, testing, documentation or maintenance. Contributions
do not automatically grant commercial-use rights; please contact the
maintainer to agree on the permission separately.

Required Notice: Copyright 2026 Stig-Rune Skansgård.

Bundled third-party software retains its own licenses; see
[third-party notices](app-desktop/packaging/common/THIRD_PARTY_NOTICES.txt).
This is a noncommercial software license, not an OSI-approved open-source
license.

## Desktop shell

The desktop application uses Kotlin/JVM and Compose Multiplatform Desktop. The
persistent `AppShell` owns a header with Program, Manual and MDI UI-mode
buttons and an icon-only ⚙ Util selector, a workspace, and fixed bottom/right screen-owned softkeys. Each window
owns a persistent `ScreenStack` for each UI mode. `ScreenStackHost` hosts all
these stacks, retaining inactive compositions and overlays (including ordinary
Compose `remember` state), while only the active stack is visible and
interactive. Switching modes never pops screens or delivers pending typed
results.

Program starts at the branded welcome screen, which opens an in-memory editor.
The editor opens a typed `FileExplorer` overlay using
`FileExplorer("/programs").open(this@Editor) { result -> ... }`. Use F1 to open
the editor and its file explorer. Selecting a fixture enables F1 Select and F2
Clear selection; F8 cancels. Right key R7 Info (Shift+F7) toggles its active
state. Mouse/touch and keyboard use the same guarded activation path. Each bank
independently inherits bindings through the active mode's screen stack when a
screen opts in; FileExplorer replaces the editor's bottom keys, but inherits and
modifies its right keys. Open the explorer, switch to Manual, navigate into its
details screen, then return to Program: the explorer and unfinished editor are
still present. Manual has a local notes screen; MDI has an unsent text buffer.
These are UI fixtures, not machine functions. They do not access actual program
files or send commands. The application starts disconnected. Util provides controller connection settings.

The module dependency direction is **app-desktop → core → infrastructure**.
`:app-desktop` contains Compose presentation and startup/composition. The
UI-independent `:core` module owns `ControllerConnection`, `ControllerSettings`,
endpoint/configuration models, validation, shared configuration state and
connection/settings workflows. `:infrastructure` provides transports, protocol,
`GrblMachineSession`, serial discovery and actual JVM Preferences storage.

`Main` constructs one core connection/settings pair above every mode/screen
lifetime. Closing the application window returns from Compose without terminating
JVM execution first, so Main cancels startup/background work, closes the TCP/serial
connection and awaits connection/application cleanup before returning. Closing the
application does not send Feed Hold, reset, unlock or replay commands. Core selects the existing
production transport from a typed endpoint and creates a fresh session per
explicit connection. All retained modes share this owner; the header observes
its actual health. Save and Connect remain explicit user intentions, while core
performs validation and Connect's subsequent persistence internally. Screens
never instantiate sessions, transports or preferences adapters. Core loads saved configuration during startup and attempts a valid saved endpoint
once after the UI signals readiness. The asynchronous attempt shares lifecycle
coordination with manual actions; no retry occurs. Failure appears only in the
header status; details remain available in Connection settings.

Select **⚙ Util** to open the live Terminal. Its unified console combines a
virtualized monospaced traffic log and an editable prompt. Enter (including the
onscreen keyboard action) submits one validated ASCII line through the shared
connection. There is no Send button. Commands can move the machine; `ok` only
acknowledges a command. Up/Down and the history softkeys browse the last
100 transport handoffs; error/timeout/uncertain results remain in recall history,
while local transport rejection is removed. History is in memory only. Unsent input and selection/caret,
filters and scrollback survive mode changes. Select text within an output entry
and use Copy selected text or the normal keyboard copy action. Selection is per
entry; cross-entry selection/export is not implemented.

Terminal and MDI provide a touch CNC/G-code keyboard with two independently paged
square key clusters stacked in a panel to the right of the console/editor,
with letters above numbers. The terminal keeps its full-height log and prompt.
Keys grow with available space from 44 to 72 dp and fit the supported minimum
1024×720 content viewport. The two clusters stay together at the bottom of their
panel; compact outer spacing preserves the configured gaps inside each grid.
The default letter grid starts `O N G P`; the numeric grid
keeps Backspace, `*`, `+` and Enter in its separated fourth column. Further pages
provide the remaining letters and macro/editing symbols. Tapping inserts at the
caret or replaces the selection without taking input focus. Terminal Enter uses
the existing guarded submission; MDI Enter inserts a local newline. Input and
keyboard pages survive mode/settings visits. Keymaps, labels, dimensions and gap
ratios are data configured through `ui.controls.KeypadLayout`; customization is
in memory, with no visual map editor or saved user preferences. See the
[configuration example and input contract](docs/ui-layout.md#cncg-code-touch-keyboard).

The terminal has no workspace action buttons or explicit Status key. Its shared
connection polls status automatically every 500 ms. F7 **Filters**, with a funnel
icon, replaces the bottom bank with Status RX, Poll TX, OK, Info, Errors and Time
on F1–F6; F7 **Back** restores the selected bottom page. Status/poll traffic starts
hidden; ordinary commands,
acknowledgments, messages, errors and alarms start visible. Filters and Clear view
apply to newly received events only; existing rows stay intact. Terminal retains
10,000 admitted events independently of core's 5,000-event diagnostic buffer.
Only actual sequence gaps produce a missed-events warning; normal rotation is silent.

F8 **Next page** cycles between two bottom-softkey pages. The first has ▶️ Cycle Start,
⏸️ Feed Hold, ⬆️ Prev Cmd and ⬇️ Next Cmd on F1–F4. The second has confirmed
Soft Reset, Copy selected text and Clear view on F1–F3; F4 is unassigned.
R6 ⏬ Follow remains available on both pages and while the filter menu is open.
Scrolling upward disables Follow; scrolling back to the bottom reactivates it.
R6 Follow jumps to the newest retained entry; new traffic preserves manual scrollback.
The prompt prefix and editable text share typography and alignment inside a single
touch-friendly input field.

Cycle Start is available with a fresh synchronized `Idle` or completed `Hold:0`
report, independent of whether a command still awaits acknowledgment. An explicit
press sends `~` once; acknowledged motion can be resumed after Feed Hold.
Run, decelerating `Hold:1`, Jog, Door, alarm, reset boundaries and stale/unknown or
disconnected status prevent Cycle Start. Normal command admission remains separate;
no motion is automatically resumed or replayed.

**R7 Connection** opens the connection form; there, **R7 Terminal** returns to the
same terminal state. Terminal has no redundant button selecting itself. This right
navigation action remains available while the bottom filter menu is open. F5 and
F6 are unassigned on the ordinary terminal pages. Terminal pages and the filter
menu survive mode changes and visits to settings. Settings and confirmations
replace covered terminal actions. Connection health and machine/alarm observations
remain in the shared header. Switching modes retains an open form and never
disconnects the shared session.

Connection settings use the bottom softkeys: F1 toggles Serial/TCP; F2/F3
select serial ports/baud or edit TCP host/port; F4 opens advanced serial settings;
F5 opens shared Post-Connect Actions; F6 connects or disconnects; F7 saves;
F8 restores the last saved form, with confirmation before discarding edits.
R7 returns to Terminal. Serial selectors show six numbered touch rows per page;
F1–F6 select, F7 pages and F8 cancels. Manual device and custom baud entry remain
available through field-owned touch keyboards. Advanced F1–F6 cycle settings;
F7 applies to the parent draft and F8 confirms discarding changed values.

Serial defaults are 115200 baud, 8N1, no flow control, DTR on and RTS off.
Explicit saved DTR/RTS values are retained. Requested control-line levels are
seeded before opening and applied to the open handle, without a reset pulse.
Discovery never opens ports; failure or an empty result permits manual entry.

Save validates and stores the complete draft without changing the active session.
Connect uses the shared status handshake before saving. Disconnect can cancel a
pending connection and requires interruption confirmation. Settings, selectors
and confirmations mask covered terminal actions. Mode changes retain the form;
returning to Terminal cancels unfinished form work and keeps completed connections.

Post-connect choices are shared by Serial and TCP: optional controller reset
(default Off), firmware info Off/$I/$I+ (default $I), parameters $#
(default On), parser state $G (default On). Queries run in that order after the
handshake and any selected reset. Each manual connection with reset enabled
requires explicit confirmation; startup always skips reset. Reset sends Ctrl+X
once and uses the existing banner/status synchronization. Queries and responses
use the session's existing queue, correlation and terminal traffic. Unsupported
commands and timeouts remain visible in connection diagnostics. Unsafe normal
correlation skips remaining queries without replay; realtime status remains
available on a live transport. Acknowledgment is not motion completion.

Startup attempts a valid saved endpoint once after UI readiness, without retry.
Missing, corrupt or unsupported records use blank endpoints and normal defaults.
Both transport drafts and post-connect choices persist in the existing record.
Storage errors remain separate from actual connection health.

The screen shows the actual endpoint and lifecycle state. Deliberate Disconnect
clears the displayed connection error and shows a neutral note that machine state
is not being updated. Unexpected connection failures remain errors; a connected
controller with missing reports still shows a stale-state warning. Core sends a background `?` status query every 500 ms after successful handshake,
independently of the selected screen/mode. Polling stops with the connection and
pauses during reset handshake. Writes are sequential, with no backlog or catch-up
bursts; a slow write delays the next poll. Missing reports still become stale
after three seconds, and polling never reconnects, resets or sends normal commands. Hardware-free tests cover validation, persistence,
serial discovery, confirmation, cancellation, timeout/reconnect and Util navigation.
Physical controller/USB line behavior remains unverified.

### Requirements

- JDK 21 (the build uses a Java 21 toolchain)
- Gradle 9.8.2 (matching the version used by Yame)

Run the app from the repository root:

```bash
gradle :app-desktop:run
```

Build and test:

```bash
gradle :infrastructure:build :core:build :app-desktop:build
```

Core can also be built/tested without compiling Compose:

```bash
gradle :core:build --configure-on-demand
```

JVM Preferences keeps the `no/grblcommander/controller` node, `endpoint` key and
v2 Properties record. Existing v1 TCP and advanced serial settings remain readable;
explicit legacy DTR/RTS values are preserved.
Core interprets/validates this record; infrastructure only reads/writes its text.

GitHub Actions builds and tests all three modules on Linux and Windows, the officially supported
desktop platforms. Each merged PR triggers a versioned GitHub Release with
native Linux (DEB/RPM) and Windows (MSI) installers. macOS is neither tested nor
packaged and is not an officially supported target; the shared Kotlin/JVM and
Compose codebase may still run there without a compatibility guarantee. Headless
Compose component tests cover navigation and overlay input isolation.
Physical-machine integration tests are not included.

### Compose UI screenshots

Capture the Terminal and MDI integration tests locally on JDK 21:

```bash
gradle :app-desktop:test -PuiScreenshots --tests '*TerminalTest'
```

The opt-in test run writes PNGs to `app-desktop/build/ui-screenshots/<scenario>/<width>x<height>.png`.
It uses the production Compose shell, its Terminal and MDI screens, and the
in-memory controller fixture. The headless Skiko test surface uses software
rendering at 1× density. It captures the application client area, so native
window decorations, platform font rendering and scaled displays can differ.
The tests check keyboard paging, command submission and input focus separately
from capture. PNGs are for visual inspection. Capture ownership is recorded alongside
the images so PR review selection can include modified capture tests without
hardcoding scenarios in CI.

| Directory | Captured state |
| --- | --- |
| `terminal` | Util Terminal with the first letter and number pages |
| `terminal-page-2` | Both second pages with a focused command draft |
| `terminal-acknowledged` | Command acknowledged while simulated execution is Run |
| `terminal-idle-first-letter` | Idle following Run and the first post-command keypress |
| `mdi` | Local MDI buffer with the first pages |
| `mdi-page-2` | Local MDI buffer with the second pages |

The Linux PR and main-push jobs capture each scenario at 1024×720, 1024×768, 1280×720,
1680×1050, 1920×1080 and 2560×1440. **1024×720 is the minimum supported
resolution** at 1× scale. Windows runs the functional tests; CI capture and
publishing run on Linux.

Open the workflow run's **Artifacts** section for
`compose-ui-screenshots-<attempt>` or, if Linux tests failed after saving
some PNGs, `compose-ui-screenshots-partial-<attempt>`. Artifacts are kept
for 14 days. Rerun all jobs to retry capture and publication together.

Successful pushes to `main` publish a complete capture set to `pr-assets/main/`
after the Linux/Windows builds, simulator integration and screenshot-tool tests
pass. Same-repository PRs publish complete Linux captures to `pr-assets/<PR number>/`
and maintain one marked review comment. A partial/failed Linux capture stays an
artifact and cannot replace published images or comments. A push to `pr-assets`
does not trigger CI, so baseline publication cannot create a workflow loop.
Fork PRs against upstream have read-only capture jobs and artifacts only; the
write-enabled publication/comment job is skipped. No upstream write token is
passed to fork code. These rules also apply within a contributor's own fork.

Review selection compares decoded RGBA pixels and dimensions against the main
baseline in the published asset snapshot; PNG metadata/compression do not count.
A scenario is included if any size is **new** or **changed**, or if its recorded
capture-test source is new/modified in the PR diff (**test-modified**), even with
identical pixels. `runAtScreenshotSizes` resolves the calling Kotlin test source
before starting the Compose runner and writes `<scenario>/capture-source.txt`.
Scenario names use lowercase letters/digits separated by hyphens and belong to
one test source. Adding a scenario requires only a test checkpoint, with no CI
scenario list. Screenshots from older captures without ownership metadata still
participate in pixel comparison. Removed paths are listed without embedding a
missing image. Differences are informational and never fail the build.

Each selected scenario embeds exactly one expanded image, preferring 1024×720
(or the first available size), with all other sizes under **Other resolutions**
in a collapsed details section. Identical captures are omitted unless their test
source changed. A clean comparison produces a short no-differences comment. If
`main/` is unavailable, captures are explicitly labelled new. All six PNGs remain
in artifacts and on `pr-assets`, independently of comment selection. Comment
image URLs use the asset commit SHA to avoid stale GitHub image caches.

Publication replaces only its target directory. Non-force Git pushes retry from
the latest tip, preserving other directories. Per-target workflow run/attempt
markers prevent older runs and reruns from replacing newer assets. One job-level
concurrency group covers publication and comment updates for each PR (and a
separate group for main); the comment also checks run/attempt ordering and that
the PR is still open at the captured head. The stable bot-comment marker is
`<!-- grbl-commander-ui-screenshots -->`. Repeated publication updates that comment
rather than creating another. Rerun **all jobs** to retry capture/publication.

For local selection/publisher regression tests (Python 3 and Pillow):

```bash
python -m pip install Pillow==12.0.0
python -m unittest discover -s .github/scripts -p 'test_*screenshots.py'
```

To preview a comment without a token, check out a `pr-assets` commit into an
`assets` directory, and save a JSON array of modified test paths as `changed.json`:

```bash
GITHUB_REPOSITORY=OWNER/REPOSITORY GITHUB_RUN_NUMBER=RUN GITHUB_RUN_ATTEMPT=ATTEMPT \
  python .github/scripts/review_ui_screenshots.py --assets assets \
  --commit ASSET_COMMIT_SHA --pr PR_NUMBER --changed-paths changed.json > review.md
```

RUN/ATTEMPT must match the selected PR directory's `.workflow-run`. For ad-hoc
links, use the immutable asset commit and actual PR number:

```markdown
![Terminal at 1024×720](https://raw.githubusercontent.com/OWNER/REPOSITORY/ASSET_COMMIT_SHA/PR_NUMBER/terminal/1024x720.png)
```

Published images persist after PR closure. Maintainers can remove a closed PR's
directory with a normal commit when its links are no longer needed. GitHub's PR
files API supplies ownership changes (at most 3,000 files); pixel selection still
works independently for larger PRs. Very large comments link secondary resolutions
instead of embedding them; if the expanded content itself exceeds GitHub's limit,
the comment links the full capture set. Capture rendering can vary with platform
and dependency versions. The first automatic baseline refresh can only be checked
once this workflow reaches `main`; until then missing-baseline behavior applies.
The fork fallback requires a live fork run to verify beyond workflow conditions.

### Machine communication and simulator tests

`:infrastructure` contains `MachineTransport`, `TcpTransport` and
`SerialTransport` (jSerialComm), GRBL parsing, and `GrblMachineSession`.
Application services own the session and supply a parent coroutine scope;
UI screens must not instantiate transports. Serial settings are explicit through
`SerialConfiguration` (115200 baud, 8N1, no flow control, DTR on / RTS off by default).
Linux serial access requires appropriate device permissions; Windows uses port
names such as `COM3`. USB/serial drivers and line-control behavior still require
verification on the target hardware.

A connection awaits a controller status report without reset/unlock. Normal
commands return acknowledgments, not proof of completed operations. Alarms keep
the transport/control connection available. `state.alarmCode` accepts both
`ALARM:n` and `Alarm:n` status reports; `state.alarm` classifies known alarms and
uses optional controller-provided descriptions from explicitly requested `$EA`.

Core-critical alarms (1, 2, 10, 17, 20) may discard a line without acknowledgment:
`state.resetRequired` blocks normal writes without expecting a late `ok`.
Non-critical alarms retain an unresolved transmitted line's response slot until
its own `ok`/`error` is consumed. Status cannot acknowledge a line. An alarm with
no outstanding line creates no phantom response expectation; alarm 11 permits
operator-requested `$H`. Nothing unlocks, homes or repeats motion automatically.

If correlation cannot be restored, explicitly call `session.reset()` (or send
`SOFT_RESET`). It resets once, keeps the transport open and awaits an
initialization banner plus fresh status before admitting new normal work.
Pending/queued lines fail and are never replayed. Check `resetInProgress`,
`resetRequired` and actual controller state afterward; reset alone does not prove
that an alarm cause is gone or that a machine is homed. A retained critical code
after reset is not itself another blocking event. Optional grblHAL all-fields
status (`STATUS_QUERY_ALL`) exposes alarm codes and the active critical-loop flag.

Real-time/status access remains available during unresolved correlation or reset
failure, subject to controller behavior. Recoverable local/write errors propagate
and populate `lastRealtimeFailure`; known transport loss marks the connection
FAILED. A timed-out reset is not retried automatically. Observe `session.state`
(`StateFlow`), checking connection health and report timestamps for freshness.
Close and await `session.awaitClosed()` when disposing the owning service.
See [alarm protocol and source findings](docs/grbl-alarms.md) for recovery limits.

`MachineSession.communication` exposes bounded, read-only TX/RX diagnostics from
the existing coordinated reader/writer. Events include session-local sequence,
timestamp, direction, category and original decoded text. TX is a transport
handoff attempt, never delivery/execution confirmation. Failed handoffs are
marked local rejection or uncertain; pre-admission rejection produces no TX.
Realtime bytes use visible ASCII or hex (`?`, `0x18`, `0x87`). A slow subscriber
cannot suspend protocol I/O. On overflow the oldest buffered event is evicted,
so responsive observers keep receiving recent traffic. `droppedCommunicationEvents`
counts each event evicted before handoff to any active subscriber once, even if
that subscriber stays stalled and no further traffic arrives. A subscriber's
sequence gaps identify its own losses; a global count need not mean the app
history missed those events. `recentCommunication` provides a bounded replay-tail
snapshot. `ControllerConnection.traffic` exposes individual events through a read-only
SharedFlow with a 1,024-event DROP_OLDEST buffer and non-suspending publication.
A separate 5,000-event diagnostic ring retains all observed traffic across sessions;
`diagnosticSnapshot()` creates a consistent immutable copy only on request.
Entries retain session, endpoint, timestamp, source sequence and a global cursor.
Cursor gaps cover upstream and core-stream loss without adding overlapping global
counters. Terminal subscribes before seeding from a diagnostic snapshot, deduplicates
the overlap and retains admitted rows incrementally in a Compose ring. Its
subscription remains active under other modes/settings. This is in-memory history,
without an extra transport reader, poller or persistent logging.

Hardware-free transport/parser/session tests run on Linux and Windows:

```bash
gradle :infrastructure:test
```

Linux simulator tests use the official upstream simulator as an external
process through the production TCP/session stack. Install Git, CMake and a C
compiler/build tool. The build explicitly selects GNU C17; the pinned core's
`powr` macro conflicts with the C23 math declaration exposed by newer
compiler/system-header defaults.

Build the pinned revision and its recorded submodules:

```bash
bash scripts/build-grblhal-simulator.sh
GRBLHAL_SIMULATOR="$PWD/build/grblhal-simulator/build/grblHAL_sim" \
  gradle :infrastructure:simulatorTest
```

Alternatively pass `-PgrblhalSimulator=/absolute/path/to/grblHAL_sim`.
The opt-in `simulatorTest` task is separate from `test`/`check` and fails if its
binary is missing. Each test process gets an isolated working directory/EEPROM,
uses `-p <port>` for raw TCP, waits for readiness with a deadline and is terminated
on success or failure. Tests cover `$I`, parsed `?` status, `$G`, command rejection, peer disconnection,
process cleanup and a hard-limit alarm during an outstanding dwell followed by
explicit reset and unlock on the same TCP session. Linux CI
runs this as a separate job. Native serial library loading/enumeration and the
serial adapter contract are tested on both desktop platforms; physical serial
ports, virtual-PTY simulator integration and controller hardware are not tested.
The upstream simulator is experimental with approximately real-time timing;
passing these tests is not evidence of physical-machine safety.

Versions start at v0.1.0. PRs labeled `major` or `minor` choose the
corresponding semantic-version bump; other PRs bump patch.
See [release process](docs/releases.md) for details, including the required PR
label validation check.

For implementation constraints,
see [AGENTS.md](AGENTS.md), [architecture](docs/architecture.md), and
the [UI layout contract](docs/ui-layout.md).

