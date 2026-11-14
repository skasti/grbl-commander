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

## Desktop shell

The desktop application uses Kotlin/JVM and Compose Multiplatform Desktop. The
persistent `AppShell` owns a header with Program, Manual and MDI UI-mode
buttons, a workspace, and fixed bottom/right screen-owned softkeys. Each window
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
These are UI fixtures, not machine functions. The application does not connect
to a controller, access actual program files or execute commands.

The UI-independent `:infrastructure` module provides TCP and serial transports,
GRBL parsing and a shared machine-session API with observable controller state.
The desktop demonstration does not instantiate a session or send machine commands.

### Requirements

- JDK 21 (the build uses a Java 21 toolchain)
- Gradle 9.8.2 (matching the version used by Yame)

Run the app from the repository root:

```bash
gradle :app-desktop:run
```

Build and test:

```bash
gradle :infrastructure:build :app-desktop:build
```

GitHub Actions builds and tests on Linux and Windows, the officially supported
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
commands return acknowledgments, not proof that an operation finished. Real-time
commands bypass queued normal lines, including during an alarm or uncertain
normal-command outcome. `ALARM:n` is exposed as `state.alarmCode` and keeps
communication available; no unlock, homing or reset happens automatically.

An interrupted transmitted line retains its response slot, and
`state.normalCommandsSynchronized` is false until its late `ok`/`error` has been
drained. Queued lines invalidated by an alarm/uncertainty are rejected, never
replayed; explicitly submit any subsequent recovery command. Status alone does
not restore line correlation. If the old acknowledgment never arrives, normal
traffic stays blocked until an explicitly requested reset/re-established session;
real-time/status access remains available. A regular `error:n` rejects only its
own command.

Local errors and write cancellation preserve a usable transport; an attempted
real-time write may have an unknown outcome and is never automatically retried.
`state.lastRealtimeFailure` records recoverable real-time/query diagnostics.
Actual transport loss marks the session FAILED. Post-connection controller
restart or a successfully/uncertainly transmitted explicit soft reset also
requires a fresh session; local reset rejection before I/O preserves it.
Observe `session.state` (`StateFlow`), checking connection health and report
timestamps before treating retained observations as live. Only a non-Alarm
status report clears a reported alarm code. Close and await
`session.awaitClosed()` when disposing the owning service.

Hardware-free transport/parser/session tests run on Linux and Windows:

```bash
gradle :infrastructure:test
```

Linux simulator tests use the official upstream simulator as an external
process through the production TCP/session stack. Install Git, CMake and a C
compiler/build tool, then build the pinned revision and its recorded submodules:

```bash
bash scripts/build-grblhal-simulator.sh
GRBLHAL_SIMULATOR="$PWD/build/grblhal-simulator/build/grblHAL_sim" \
  gradle :infrastructure:simulatorTest
```

Alternatively pass `-PgrblhalSimulator=/absolute/path/to/grblHAL_sim`.
The opt-in `simulatorTest` task is separate from `test`/`check` and fails if its
binary is missing. Each test process gets an isolated working directory/EEPROM,
uses `-p <port>` for raw TCP, waits for readiness with a deadline and is terminated
on success or failure. Tests cover `$I` identification, parsed `?` status, `$G`
(non-motion modal reporting), command rejection followed by further communication, peer disconnection and process cleanup. Linux CI
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
