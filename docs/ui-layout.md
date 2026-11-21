# Desktop UI layout

GRBL Commander uses a persistent operator-interface shell implemented with
Compose Multiplatform Desktop.

## Persistent shell

| Region | Responsibility |
| --- | --- |
| Header | App identity, mode selection, current context, and connection health |
| Workspace | Content from the active retained mode stack |
| Bottom softkeys | Eight fixed positions, F1–F8 |
| Right softkeys | Seven fixed positions, R1–R7 |

The header spans the window. The right bank spans the workspace and bottom bank.
The workspace and bottom bank share the remaining width. The default header,
bottom bank and right bank measure 78 dp, 94 dp and 142 dp respectively. The
minimum supported viewport is 1024 × 720 pixels at 1× density. Verify legibility
and clipping at both configured [screenshot sizes](ui-screenshots.md).

`AppShell` accepts workspace content and optional header controls. `ShellState`
carries display-only header information, including the shared connection's
health. The shell renders the active stack's softkeys outside the workspace
input boundary.

The workspace, icon and header use bundled Compose Resources.
`grbl_commander_icon.png` (384 × 256) preserves transparent corners and
`grbl_commander_splash.jpg` (1024 × 768) supplies the splash. Use the typed
`Res.drawable` accessors with `org.jetbrains.compose.resources.painterResource`.
Images retain their proportions with `ContentScale.Fit`. The window manager
determines how it displays the application icon.

## Operator interaction and modes

The shell supports touch, keyboard and mouse. The header selects Program,
Manual, MDI or icon-only ⚙ Util. Each window retains a stack per mode.
Switching context preserves open screens and unsent drafts, with no effect
on controller execution.

Util provides the live Terminal and controller connection settings. Program
provides the welcome screen and an in-memory editor/file explorer, Manual
provides a details screen with local notes, and MDI provides a local text
buffer. Program execution, jogging, MDI execution, DRO and probing remain planned.

Modes own their workspace content and contextual actions. Shared controls
render and dispatch typed actions. Core and the session govern machine access.

## System guides

- [Screen navigation, mode stacks and typed results](screen-stack.md)
- [Shared workspace dialogs](dialogs.md)
- [Workspace sizing and softkeys](layout-grid-unit.md)
- [CNC and text touch keypads](keypads.md)
- [Controller connection and settings](controller-connection.md)
- [Util Terminal and traffic diagnostics](terminal.md)
