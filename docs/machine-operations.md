# Machine operations

## Line-based operations

`ControllerConnection.startOperation` starts a line-based run against the
current session. The returned `Operation` identifies the session, the run,
its source workflow and the caller's source ID. `Operation.Source` is a
string-backed value with constants for MDI and Program. Callers can supply
other values such as `Operation.Source("COMMAND")`. The operation retains
an immutable copy of the source lines and exposes its latest progress through
a read-only state flow.
Line positions in progress are one-based. The connection exposes the latest
operation handle and whether a run still reserves normal dispatch.

Starting requires a fresh, synchronized Idle report and exclusive normal
dispatch. A run excludes Terminal and other operations from admission through
physical completion. Post-connect queries must finish before a run can start.
Status polling and permitted realtime controls remain available. A Hold report
pauses dispatch of further blocks. Cycle Start can resume a confirmed Hold:0,
and streaming continues when the controller reports Run or Idle.

The operation projects the [session buffer's progress](controller-connection.md)
onto its source lines. It preserves explicit N words, ignores ambiguous
duplicates and can add N words to batches composed entirely of simple G0/G1
blocks when they fit the line length limit. Blocks outside that subset are sent
as written. Line reporting depends on the controller configuration. An absent
or unmapped Ln clears the active line.

The elapsed duration is the sum of intervals between consecutive fresh reports
that identify the same line and both report Run. A first observation begins at
zero. Intervals touching Hold are excluded. Missing, stale or unmapped reports
clear the estimate, and a skipped sample starts a new estimate. The report
timestamp is available with the progress for clients displaying freshness.

The final OK leaves the operation active until a later fresh Idle report arrives.
A correlated error fails the operation at the reported line. Alarm, reset, loss
of command correlation, command timeout and disconnection interrupt the run.
A stale status report clears execution progress while the run waits for fresh
status or an explicit reset or disconnect. For a stopped run with a connected
session, a subsequent synchronized Idle report releases normal dispatch.
The operation handle retains its outcome across reconnects.

## Planned workflows

Future workflows can represent homing, jogging, probing, controller-hosted
program execution and other controller work through an operation. They can
use completion criteria appropriate to the work involved. The desktop Manual
screen uses local demo state. [MDI](programs-macros-tools.md#mdi-execution)
uses line-based operations.

## Global controls

Cycle Start, Feed Hold, Reset and overrides should be available through shared
application actions.

Their behavior depends on the active operation and actual controller state, not
solely on the currently displayed UI mode.

For example, Cycle Start may start a prepared MDI buffer, start a selected
program, or resume an operation held by the controller.

The UI must clearly distinguish starting a new operation from resuming an
existing one.

## Jogging

Jogging uses the controller's dedicated jogging protocol when available.

Jogging should support both incremental and continuous forms.

Continuous jogging must account for touch release, cancellation, application
focus loss, connection interruption, and delayed input events. Its safety must
not depend exclusively on receiving a touch-release event.

## Operator interaction

Controller messages and state transitions may require operator intervention,
particularly during tool changes and macros.

The planned operator-interaction system should handle:

- Informational messages.
- Warnings and alarms.
- Program pauses.
- Tool change instructions.
- Actions requiring explicit operator confirmation.

Messages are not automatically interpreted as blocking requests. Resumption and
acknowledgment must follow the controller's actual protocol and state.

## Digital readout and work coordinates

The read-only Compose `DigitalReadout` control in `ui.components.controls`
takes a `DigitalReadoutState` from its caller. Axes appear in caller order,
with the work coordinate emphasized and the machine coordinate alongside it.
The state also carries the active WCS, units, current feed rate, spindle RPM,
and feed, rapid and spindle override percentages. An axis can specify a
different unit, such as degrees on a rotary axis.

Coordinates use aligned monospace digits and a decimal point, with three
decimal places or four if any axis uses inches. Feed uses one decimal place
and the supplied units per minute. Missing and non-finite readings appear as
`—`. The caller supplies the availability state. Live and stale can both
show supplied readings, with stale values muted and labeled. Disconnected
and unavailable states show placeholders for readings while retaining supplied
axis labels and unit metadata.

The caller sizes and places the control with its Compose modifier. The
axis list scrolls when the available height is too small, with the column
headings and telemetry still visible. Headless tests use deterministic data
in an MDI-sized fixture. The [MDI workspace](programs-macros-tools.md#local-mdi-editor)
uses a placeholder. Live DRO integration and persistent placement across modes
are planned.

### Planned coordinate editing

Where permitted, operators will be able to edit coordinates through
context-sensitive actions such as

- Set to zero.
- Set to a specified value.
- Halve the displayed coordinate value.
- Apply a relative adjustment.

Editing a work coordinate changes the appropriate coordinate offset. It must not
implicitly request machine movement.

Machine coordinates are read-only during normal operation.

## Planned workspaces

| Workspace | Planned responsibilities |
| --- | --- |
| Manual | Jogging, homing, positioning and coordinate setup |
| MDI | Editing and executing a temporary program buffer |
| Program | Program selection, editing, preview and execution |
| Probing | Macro-driven probing |
| Macros | Discovery, editing, execution and synchronization |
| Util | Further diagnostics |
| Tool management | Dedicated mode or a workspace accessible from Program and Macros |

Candidate shared components include unit-aware controls, a coordinate editor,
macro parameter dialogs, tool table and inventory views, program block navigation
and a toolpath viewer. Add shared components when actual workflows establish
their contracts.

See [Programs, macros and tools](programs-macros-tools.md) for program execution,
controller-hosted workflows and implementation priorities.
