# GRBL Commander architecture

GRBL Commander is a Kotlin/JVM desktop operator interface for GRBL-compatible
CNC machines, with grblHAL as the primary target. The Compose shell is designed
for touchscreen use and supports mouse and keyboard input. The application
presents controller observations, takes operator input and coordinates
communication. The controller owns motion planning, real-time execution,
machine I/O and controller-side macros.

## Modules and dependencies

Three Gradle modules run in one desktop process. Compile-time dependencies
follow the arrows below.

```mermaid
flowchart TD
    desktop[":app-desktop · Compose presentation and wiring"]
    core[":core · application workflows and contracts"]
    infrastructure[":infrastructure · GRBL, I/O and persistence"]
    desktop --> core --> infrastructure
```

| Module | Owns |
| --- | --- |
| `:app-desktop` | `Main` wires startup and shutdown. `CommanderApp` assembles concrete mode roots and shared UI. Screens own interaction, view state and drafts. |
| `:core` | `core.connection` owns the application connection/settings API, endpoint and draft models, validation, settings schema, session factory, session-bound command buffering, operation coordination, polling and shared traffic history. Shared application workflows and domain rules belong here. |
| `:infrastructure` | `machine.session`, `machine.protocol` and `machine.transport` own session synchronization, GRBL framing, TCP/serial I/O and discovery. `configuration` adapts JVM Preferences to opaque record storage. |

Core re-exports the infrastructure API used in its public contracts.
Presentation can read shared values such as `MachineSnapshot` and traffic
events. Screens access workflows through core. Core selects and constructs
the session and transport at its connection boundary. Infrastructure depends
on neither core models nor Compose.

## Desktop package ownership

Package names are relative to `no.grblcommander.desktop`. Edges show the
package hierarchy.

```mermaid
flowchart TD
    desktop["desktop · Main"]
    desktop --> mode["mode"]
    desktop --> ui["ui · CommanderApp and shared state utilities"]
    mode --> operator["mode.program · mode.manual · mode.mdi"]
    mode --> utils["mode.utils"]
    utils --> connection["mode.utils.connection"]
    utils --> terminal["mode.utils.terminal"]
    ui --> layout["ui.layout"]
    ui --> navigation["ui.navigation"]
    ui --> components["ui.components"]
    components --> controls["controls"]
    components --> dialogs["dialogs"]
```

| When adding | Where it belongs |
| --- | --- |
| An operator mode, screen, or feature-specific dialog | Keep the screen, local state, helpers and fixtures in its owning `mode.*` package. Util's connection and Terminal flows have separate owners. Use a shared mode package only if concrete flows are genuinely shared. Wire mode roots in `CommanderApp`. |
| A reusable visual control | Use `ui.components.controls` or `ui.components.dialogs` for general input or dialog presentation. Accept UI state, data and callbacks. Keep session access, discovery and feature-specific decisions with the caller. Shared UI infrastructure stays independent of `mode.*`. |
| A stack, softkey or layout contract | Keep routing, screen lifecycles, typed results and softkey binding in `ui.navigation`. Keep shell regions, tiles and workspace sizing in `ui.layout`. |
| A state owner | Keep feature interaction state in the screen and reusable input state in its control. Move shared application state, workflows, validation and persistence policy into core. Obtain live machine facts from the session through the shared connection. |
| A protocol, storage or platform adapter | Put GRBL semantics, technical state, device I/O and storage adapters in infrastructure. Isolate native desktop concerns such as windowing and packaging at their integration boundary. Keep core portable and independent of Compose, native APIs and transport-specific libraries. |
| A package or Gradle module | Extend the existing owner when responsibilities are cohesive. Create a package for a distinct, implemented responsibility. Add a Gradle module only for a concrete benefit such as dependency isolation, separate reuse or independent testing. |

Name screens by their operator-facing role, for example `Editor`,
`FileExplorer`, `ManualMode` or `Terminal`. Use a `Screen` suffix only to
distinguish a same-named model or service. Give each primary top-level type a
same-named file. A small supporting result or helper may share its owner's file
when it forms part of the same contract and has no independent use.

## Design constraints

- **One communication path.** Coordinate all writes through the shared session.
  Realtime commands must stay responsive independently of ordinary line
  acknowledgments. Keep UI code independent of machine transports. See the
  [session contract](grbl-session.md#handshake-and-command-acknowledgment).
- **The controller is authoritative.** Check confirmed state before an
  operation. Treat loss of communication as unknown machine state and show
  stale values explicitly. Determine physical completion from controller
  observations. See [what an acknowledgment represents](grbl-session.md#handshake-and-command-acknowledgment).
- **Machine safety.** Prevent conflicting motion or program operations.
  Require explicit confirmation where appropriate. Distinguish warnings,
  alarms, holds and emergency-stop conditions. Reconnect must never replay
  uncertain commands or resume interrupted motion. Resumption of interrupted
  operations always requires explicit action. Physical emergency-stop circuits
  and machine-level interlocks operate independently. Use established grblHAL
  protocol semantics.
- **Separate analysis, visualization and execution.** Report unresolved G-code
  and uncertain progress. A preview cannot prove safe execution. Preserve
  program and macro source and use controller-defined macro interfaces. See
  [controller compatibility requirements](grbl-session.md#controller-compatibility-requirements)
  for optional capabilities.
- **Linux and Windows share the JVM codebase.** Prioritize Linux touchscreen,
  serial/USB and kiosk operation, including scaling, X11/Wayland and device
  permissions. Isolate platform integrations and document intentional platform
  differences, including Windows alternatives or limitations. Keep Linux-only
  dependencies out of shared code. Optional native integrations should degrade
  gracefully. JVM modules are shared across platforms. Introduce platform-specific source sets
  only when concrete behavior requires them. macOS is outside the supported
  release matrix.
- **Verification.** Follow the [testing strategy](testing.md#verification-strategy)
  for machine-independent tests, cross-platform coverage and hardware checks.

## System guides

| Guide | Scope |
| --- | --- |
| [Desktop UI layout](ui-layout.md) | Persistent shell, operator modes and supported interaction model |
| [Screen navigation and softkeys](screen-stack.md) · [Dialogs](dialogs.md) | Retained stacks, input isolation, typed results, softkey placement, binding and paging, and dialog primitives |
| [Workspace grid sizing](layout-grid-unit.md) · [Touch keypads](keypads.md) · [G-code editor](gcode-editor.md) | Grid sizing, keymaps and reusable editing |
| [Controller connection and settings](controller-connection.md) | Core lifecycle, startup, persistence, polling, admission, traffic aggregation and the Util form |
| [GRBL session](grbl-session.md) · [grblHAL alarms](grbl-alarms.md) | Transports, protocol, correlation, recovery, session events and firmware alarm details |
| [Terminal and traffic diagnostics](terminal.md) | Operator console, traffic presentation and command recall |
| [Machine operations](machine-operations.md) · [Planned programs, macros and tools](programs-macros-tools.md) | Operation lifecycle and progress, DRO presentation, planned jogging, analysis, controller macros and tools |
| [Building and testing](testing.md) · [UI screenshots](ui-screenshots.md) · [Release process](releases.md) | Verification commands and coverage, visual review and native packaging |
