# Planned machine operations

These are design requirements for future operation workflows. The
[Terminal](terminal.md) already sends individual commands and exposes guarded
realtime actions through the [controller connection](controller-connection.md).
The Manual and MDI screens use local demo state.

## Coordination requirements

Application operations represent user intentions rather than raw protocol
commands.

Examples:

- Home machine.
- Jog an axis.
- Set a work coordinate.
- Execute an MDI buffer.
- Start or resume a program.
- Run a macro.
- Read controller settings.
- Synchronize macros.

Shared coordination in core must cover the following responsibilities.
The need for a dedicated operation abstraction is tracked in
[issue #38](https://github.com/skasti/grbl-commander/issues/38).

- Validating operation preconditions.
- Preventing conflicting operations.
- Managing operation ownership and lifecycle.
- Dispatching commands through the machine session.
- Monitoring controller state.
- Reporting progress, completion, failure, and interruption.

Only one operation that owns machine motion or program execution may be active
at a time. Monitoring and permitted real-time controls remain available
independently.

An operation must not be considered complete solely because its final G-code
block received an `ok` response.

Program streaming, controller-hosted execution, and macro execution may require
different completion and progress strategies.

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
