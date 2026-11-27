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

The planned DRO should remain visible in most operating contexts.

It should display the position of every available axis in both machine coordinates and
the active work coordinate system.

Where permitted, coordinates can be edited through context-sensitive actions,
including:

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

Candidate shared components include a G-code editor, unit-aware controls, DRO
and coordinate editor, macro parameter dialogs, tool table and inventory views,
program block navigation and toolpath viewer. Add shared components when actual
workflows establish their contracts.

See [Programs, macros and tools](programs-macros-tools.md) for program execution,
controller-hosted workflows and implementation priorities.

## Source-owned controller operations

This API is implemented in `core.controller`; desktop workflows use
`core.connection.ControllerConnection`.

A caller constructs `core.controller.Operation(lines, source, now)` and retains
the same object from admission through its terminal result. `source` is a
`CommandSource` identifying Terminal, MDI or a named Program. Each queued
`Command` carries that source and an optional one-based source line. Construction
snapshots and validates the complete source and indexes unambiguous N words. An
accepted operation is single-use. The `Controller` owns session admission and
creates the bounded [command buffer](controller.md) using the operation's
capacity and the captured session epoch. It passes that buffer to
`Operation.prepare` together with the initial machine snapshot. The operation
binds buffer reports to its progress recorder, and the controller forwards
session snapshots through the operation's state handler. Both paths provide a
snapshot directly, so the operation does not read the session. It deduplicates
equal status observations delivered through the session flow and buffer reports.
After a command result changes progress, the operation reevaluates the snapshot
carried by that event. The controller monitors the accepted command epoch and
interrupts the operation if it changes. The producer prepares wire text one line
at a time and waits for buffer space. Interruption cancels the producer, closes
the buffer and waits for its worker to settle.

Preparation binds the buffer while the operation remains in the READY phase.
Starting changes it to RUNNING immediately before launching the execution job.

`Controller` and `Operation` use an injectable clock for the shared
`MachineSnapshot.isFresh` policy.

Progress distinguishes enqueue, full write, acknowledgment, uncertain write,
rejection and sampled execution. An execution position comes from a fresh Run
or Hold report with a mapped Ln received after the corresponding line was
written. A successful last reply records a report-sequence boundary. Physical
completion requires a fresh synchronized Idle report beyond that boundary.
