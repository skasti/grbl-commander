# Planned programs, macros and tools

This guide defines requirements for program handling, execution,
controller-side macros and tools. MDI supports local editing and execution.
The [Program navigation example](screen-stack.md#runnable-example) uses
in-memory files. Program file access, analysis, toolpaths, macro workflows
and tool management remain planned.

## Local MDI editor

MDI uses the [G-code editor control](gcode-editor.md) with its default
line validation, navigation and display softkeys. Saving a line is local.
Switching modes preserves the buffer, editor state and keypad pages.

The upper-right workspace contains a placeholder digital readout labeled
`DRO`. Its height is 40% of the workspace height and its width follows a 4:3
aspect ratio. CODE and NUM+ sit side by side beneath it, to the right of the
editor, with one grid unit between them.

## MDI execution

F1 starts the saved buffer when the shared connection admits a new operation.
MDI shows the condition blocking Cycle Start when a new run is unavailable.
During an active run, MDI shows the saved rows in the editor's
[read-only mode](gcode-editor.md#source-and-input). MDI starts one
[core operation](machine-operations.md#line-based-operations) from the saved
lines and displays the result of that run across mode changes.

F2 sends Feed Hold. During a controller hold, F1 shows Resume and becomes
available when the controller reports Hold:0. F3 opens Soft Reset confirmation.
A confirmed reset interrupts the operation. Starting another run requires an
explicit Cycle Start.

MDI displays the executing line and sampled elapsed time only for a fresh, mapped
observation of the exact run started by this MDI screen.
The last-sent marker stays available after completion while the row matches the
sent source. Status reports leave the edit selection and draft intact. The
status line shows sampled execution progress when available, reports when the
active line is unknown, and distinguishes a sent or accepted block from
completion. It identifies a rejected line or reports an interrupted run with
unknown completion.

## Programs and G-code

### Program Model

A CNC program is a first-class application object.

It contains the original source, ordered blocks, optional file identity, editing
state, and analysis results.

Programs may originate from:

- Local files.
- Temporary MDI buffers.
- Controller-hosted storage.
- Generated or imported content.

MDI and Program modes share the same underlying program model and execution
infrastructure.

### G-code Analysis

G-code parsing and analysis are separate from execution.

The planned analyzer should provide the following.

- Syntax and structural analysis.
- Identification of commands, words, expressions, and macros.
- Modal state tracking.
- Block-to-toolpath associations.
- Static extraction of tool requirements and macro invocations.
- Diagnostics for unsupported or unresolved constructs.

The parser must accommodate grblHAL-specific features, including expressions,
variables, flow-control constructs, and extensions where supported.

Follow the [separation of analysis and execution](architecture.md#design-constraints)
when using analyzer results.

Dynamic expressions, controller variables, macro calls, and conditional
execution may prevent complete static interpretation. The analyzer must
represent uncertainty explicitly instead of producing an apparently complete but
incorrect result.

### Toolpath Visualization

Toolpath generation is independent of the UI renderer.

The analysis layer produces geometry and metadata associated with source blocks.

The initial renderer should use Compose Canvas/Skia for 2D and 2.5D visualization,
including axis projections, zoom, pan, rapid/feed distinction, and
selected-block highlighting.

Hardware-accelerated 3D visualization and material removal simulation may be
introduced later behind the same geometry model.

Rendering must distinguish resolved geometry from paths that cannot be evaluated
reliably.

### Program Execution

The execution model should support these strategies.

- Host-streamed G-code.
- Controller-hosted file execution, when supported.
- Single macro invocation.

Each strategy exposes a consistent application-level lifecycle while retaining
its protocol-specific behavior.

Execution progress must distinguish source position, submitted blocks,
acknowledged blocks, and reported controller execution position where available.
An `ok` acknowledgment confirms controller acceptance, not physical completion.
The final acknowledgment alone must not mark a program as finished; completion
requires appropriate controller observations after buffered motion has drained.

#### Host-streamed line tracking

For controllers supporting line-number reporting, the application should track
the actual executing program block through `Ln:<n>` in realtime status reports,
separately from the most recently submitted and acknowledged blocks. On grblHAL,
this requires an `N` word in transmitted G-code and the line-number reporting
option in `$10` (bit 2, value 4). Do not silently replace other `$10` options.

- Preserve the original program source. Maintain an explicit mapping between
  source line/block identity, any original `N` word, and the `N` value sent to
  the controller. Where safe, assign unique numbers during host streaming for
  reliable source correlation. Never insert a duplicate `N` word or blindly
  renumber existing `N` words that might be referenced by flow control or
  other controller-side constructs. Account for the transmitted line-length limit.
- Treat `Ln:` as a sampled observation of controller execution, not an event
  emitted for every started or completed source line. Brief motions may pass
  between status reports. Non-motion blocks, macros, and controller-hosted
  execution may not have a one-to-one mapping to source lines. Report an
  unavailable or ambiguous position explicitly rather than inventing one.
- Support large programs (at least 100,000 lines) without imposing a file-size
  limit based on the controller's command or planner buffers. Current grblHAL
  accepts `N` numbers through 10,000,000, which is a line-number limit, **not**
  a program-length limit. Other firmware may impose lower limits. Loading,
  indexing, editing, and rendering large programs should remain responsive.
- Keep ordinary command submission and `ok`/`error` correlation in the
  existing session/command-buffer contracts; consume controller status through
  the same session. Correlate reported execution position with the program at
  the program-execution layer, not within the transport or status parser.
- Handle reset, disconnect, alarm, unexpected status, and lost `Ln:` reports as
  potentially uncertain execution progress. A reported line number alone must
  not authorize an automatic restart or resumption from that source line.

See the [grblHAL realtime report](https://github.com/grblHAL/grblhal_docs/blob/main/content/markdown/04-Reference/01-system-commands-reference.md#realtime-report)
and [grblHAL parser limit](https://github.com/grblHAL/core/blob/master/gcode.c)
for the controller-specific behavior.

## Macro System

Macros are first-class programs executed by the controller whenever the
controller supports the required functionality.

GRBL Commander does not implement a competing interpreter for controller-side
macro logic.

A macro service should cover the following responsibilities.

- Macro discovery and cataloging.
- Source editing.
- Macro metadata and parameters.
- Invocation construction.
- Local/controller version comparison.
- Synchronization and backup.
- Associations with application functions and UI categories.

### Macro Definitions

Macro interfaces are determined by the macro implementation and the controller's
supported argument-passing conventions.

For example, a `G65 P200` invocation follows the interface expected by
`P200.macro`, regardless of whether the caller is a Fusion postprocessor,
another macro, or the user interface.

Optional metadata describes how a macro should be presented:

- Display name and description.
- Category.
- Parameter names, types, units, defaults, and constraints.
- Available operator actions.
- Supported machine configurations.

Metadata may be stored in sidecar files or derived partially from source
comments and static analysis.

The source macro remains executable without GRBL Commander or its metadata.

### Macro-Driven Workflows

Probing and tool-related functions are primarily composed from controller
macros.

A generic macro parameter UI can generate operator dialogs without hardcoding
individual probing operations.

Complex workflows, including tool changes and tool-length probing, remain
controller-owned when implemented by controller-side macros.

Host-coordinated workflows may be supported for operations requiring
application-side interaction, but must explicitly define failure and
interruption behavior.

### Macro Synchronization

Macros may exist in:

- A configured local directory.
- Controller storage.
- An external version-controlled repository.

The synchronization service compares versions, identifies conflicts, and allows
the operator to choose which changes to apply.

Critical macros must not be replaced during execution.

Synchronization should support backups, transfer verification, and explicit
operator approval before overwriting controller files.

## Tool Management

Tool management distinguishes three concepts:

**Program Tool Requirements** describe which tools a particular CNC program
requires.

**Machine Tool Inventory** describes which tools the machine has registered,
their locations, and any available descriptions.

**Tool Geometry and Compensation** describe measured offsets and geometry used
by the controller.

These are related but are not interchangeable.

### Program-Declared Tools

Programs may declare their required tools through macro invocations.

The application can analyze these declarations, resolve them against known macro
definitions, and display a Program Setup view before execution.

The application must not assume that analyzing a declaration means the
controller has registered the corresponding tool.

### Controller Tool Data

The application should support grblHAL tool tables when available, including
their supported offsets, names, and pocket assignments.

It should also support macro-managed tool inventories, as used by custom
tool-management macros.

The domain model should not require a specific tool-table implementation.

Controller-reported data is authoritative for the state and compensation
actually used by the controller. Optional local metadata may enrich the
presentation.

The application must avoid creating competing, silently synchronized sources of
truth for rack assignments or tool offsets.

## Implementation priorities

1. Extend the existing shell, navigation, Terminal and connection foundation
   with DRO, broader global controls and overrides, homing and jogging.
2. Build on the local editor and MDI keypad with real program loading and
   editing, shared program/MDI execution support, host streaming, lifecycle and
   initial toolpath visualization.
3. Add controller file access, macro catalog and editor, metadata, parameter
   dialogs, synchronization, tool requirements, setup and inventory, and
   macro-driven probing.
4. Extend visualization, probing and machine configuration. Add
   controller-hosted program execution, expanded protocol and transport
   compatibility, and optional host-coordinated workflows as capabilities allow.

Add shared components as real workflows need them. See the
[verification strategy](testing.md#verification-strategy) for coverage required
as these features become available.

## References

- [grblHAL SD card and macro plugin](https://github.com/grblHAL/Plugin_SD_card)
- [Existing grblHAL macro examples](https://github.com/skasti/grblhal-macros)
