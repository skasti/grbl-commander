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

Enter a TCP host/IP and its configured port (no fixed port is assumed), or refresh
the optional serial-port list and/or enter a device manually. Serial defaults are
115200 baud, 8N1, no flow control and DTR/RTS false; Advanced exposes all options.
Discovery does not open ports or change control lines. Empty discovery or native/
permission failures do not prevent manual entry.

**Save** asks core to store the selected transport/settings without
changing the active connection. **Connect** validates, connects through the shared
owner, waits for a controller status handshake and then saves. **Disconnect** can
cancel a pending handshake, but is disabled during Save or an ongoing Disconnect.
After a successful handshake it waits for Connect's settings save before disconnecting.
Replacing/disconnecting an active or connecting session
requires confirmation through a modal screen on the Util stack: **F1 confirms**
and **F8 cancels** using the bottom softkeys. The modal has no action buttons in
its workspace and hides the covered screen's actions until it closes. Reusable
`ModalDialog<R>` supports other messages/typed choices through the same stack.
Confirmation does not stop physical motion or replay commands.
Close cancels an unfinished form action; it does not disconnect a completed session.
Storage errors are displayed separately from actual connection health. Settings
load when opening the form; missing/corrupt/incomplete records use blank endpoint
fields and normal serial defaults. Startup attempts a valid saved endpoint once, after UI readiness; it never retries.

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
v1 Properties record. Existing TCP and advanced serial settings remain readable.
Core interprets/validates this record; infrastructure only reads/writes its text.

GitHub Actions builds and tests all three modules on Linux and Windows, the officially supported
desktop platforms. Each merged PR triggers a versioned GitHub Release with
native Linux (DEB/RPM) and Windows (MSI) installers. macOS is neither tested nor
packaged and is not an officially supported target; the shared Kotlin/JVM and
Compose codebase may still run there without a compatibility guarantee. Headless
Compose component tests cover navigation and overlay input isolation.
Physical-machine integration tests are not included.

### Machine communication and simulator tests

`:infrastructure` contains `MachineTransport`, `TcpTransport` and
`SerialTransport` (jSerialComm), GRBL parsing, and `GrblMachineSession`.
Application services own the session and supply a parent coroutine scope;
UI screens must not instantiate transports. Serial settings are explicit through
`SerialConfiguration` (115200 baud, 8N1, no flow control, DTR/RTS low by default).
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

