# Desktop UI layout

GRBL Commander uses a persistent operator-interface shell implemented with Compose Multiplatform Desktop.

## Current implementation

The shell is composed of four regions, matching the static HTML/CSS and screenshot design references:

| Region | Current responsibility |
| --- | --- |
| Header | Application identity, current context and explicit connection status |
| Main workspace | A single startup/welcome screen |
| Bottom softkeys | Eight fixed slots, F1 through F8 |
| Right softkeys | Seven fixed slots, R1 through R7 |

The header spans the entire screen width. The right-side softkeys span the workspace and bottom-softkey row. The main workspace and bottom keys share the remaining width. The initial default sizes are 78 dp for the header, 94 dp for the bottom keys and 142 dp for the right column; they may be adjusted for screen sizes and touch targets.

`AppShell` owns the persistent regions and accepts a Compose content slot for the workspace. `ShellState` contains display-only header information and softkey labels. All softkeys are noninteractive placeholders, not machine commands.

The startup screen is deliberately disconnected from any controller. It does not simulate a real connected machine or expose motion operations.

## Architectural requirements

As the UI grows, modes own their workspace content and contextual actions, not the global layout. Shared softkey components must support labels, icons, disabled state and typed actions when those capabilities are implemented. The action dispatch layer must remain separate from GRBL transports and physical execution.

Machine position/DRO, navigation, mode-specific pages, live connection state, programmable actions, and physical CNC operations are planned and are not part of the startup shell.

See [architecture.md](architecture.md), especially sections 2, 5 and 10, and the repository [AGENTS.md](../AGENTS.md).
