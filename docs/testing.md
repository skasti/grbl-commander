# Building and testing

## Prerequisites

Install JDK 21 and Gradle 9.8.2. Run the commands below from the repository root.
The repository uses a system Gradle installation.

## Build and test

Run the same module builds that GitHub Actions runs on Linux and Windows.

```bash
gradle :infrastructure:build :core:build :app-desktop:build
```

Build and test core without configuring or compiling the Compose desktop module.

```bash
gradle :core:build --configure-on-demand
```

Core tests cover connection lifecycle, handshakes, cancellation, diagnostics,
settings validation, legacy record compatibility, shared settings state and
simulated restarts. Core and desktop tests share a simulated transport through
Gradle test fixtures. Headless Compose tests cover navigation, overlay input
isolation and confirmation, plus connection form validation, serial discovery,
persistence, cancellation, timeout, reconnect and concurrent operations.

For visual verification, see [Compose UI screenshots](ui-screenshots.md). For
native distributions and package smoke tests, see [Versions and releases](releases.md).

## Infrastructure tests

The infrastructure suite runs on Linux and Windows. It covers GRBL framing and
parsing, TCP loopback, native serial library loading and enumeration, serial
lifecycle through an injected native adapter, and in-memory session correlation
and failure handling.

```bash
gradle :infrastructure:test
```

## grblHAL simulator integration

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
uses `-p <port>` for raw TCP and waits for readiness with a deadline. Bounded
graceful or forced cleanup runs in `finally`, including on assertion failures.
Tests cover `$I`, parsed `?` status, `$G`, command rejection, peer disconnection,
process cleanup and a hard-limit alarm during an outstanding dwell followed by
explicit reset and unlock on the same TCP session. Linux CI runs this as a
separate job. Physical serial ports, virtual-PTY simulator
integration and controller hardware are outside the automated coverage.
The upstream simulator is experimental with approximately real-time timing;
passing these tests is not evidence of physical-machine safety.

## Verification strategy

Machine-independent behavior must be testable without CNC hardware. Prefer
recorded reports for framing and parsing, in-memory transports and the upstream
simulator for state transitions and failure handling, and deterministic
machine-state fixtures for the UI. Linux and Windows CI cover shared behavior
and relevant platform integrations. Native packaging is checked on the target OS.

As the planned workflows gain implementations, cover streaming flow control,
execution interruption and failure, conflicting operation requests and
cancellation. Use representative G-code and grblHAL macro repositories for
analysis, invocation, parsing and Program Setup regressions. Simulated scenarios
should include delays, missing responses, errors, alarms, disconnects and tool
change pauses as applicable.

Linux is the primary environment for physical CNC integration. Verify relevant
shared behavior on both supported operating systems before claiming general
support. Automated tests and simulated controller responses cannot establish
physical-machine safety. State exactly which controller and hardware behavior
was exercised before relying on a motion-related feature.
