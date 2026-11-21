# Planned programs, macros and tools

This guide defines requirements for program handling, execution,
controller-side macros and tools. Program and MDI currently demonstrate local
editing and input. The [navigation example](screen-stack.md#runnable-example)
uses in-memory files. Real program access, analysis, toolpaths, execution,
macro workflows and tool management remain planned.

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

**Static analysis is not a substitute for controller-side G-code execution.**

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
