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
The workspace and bottom bank share the remaining width. The header is 78 dp high.
Both banks use the same tile size at a 4:3 width-to-height ratio. Tiles grow to
the largest whole-pixel size that fits both banks, including their gaps and padding.
Both banks use a gap of 30% of the tile height, rounded to a whole pixel, matching
the ordinary spacing ratio of the [touch keypads](keypads.md#sizing-and-spacing).
The bottom bank centers its tiles horizontally. The right bank aligns R7 with
the bottom edge of the F1–F8 tiles. The remaining area is the workspace.

The native window reserves a minimum client area of 1024 × 720 logical pixels.
The outer minimum also includes the platform's title bar and borders.
Verify legibility and clipping at both configured [screenshot sizes](ui-screenshots.md).

`AppShell` accepts workspace content and optional header controls. `ShellState`
carries display-only header information, including [connection health and freshness](controller-connection.md#freshness-and-background-polling).
The header context includes the selected endpoint. The shell renders the active stack's softkeys outside the workspace
input boundary.

The workspace, icon and header use bundled Compose Resources from
`src/main/composeResources/drawable`.
`grbl_commander_icon.png` (384 × 256) preserves transparent corners and
`grbl_commander_splash.jpg` (1024 × 768) supplies the splash. Use the typed
`Res.drawable` accessors with `org.jetbrains.compose.resources.painterResource`.
The deprecated classpath `androidx.compose.ui.res.painterResource(String)`
API is unsupported for these assets. Images retain their proportions with `ContentScale.Fit`. The window manager
determines how it displays the application icon.

## Operator interaction and modes

The shell supports touch, keyboard and mouse. The header selects Program,
Manual, MDI or icon-only ⚙ Util. The Util control has accessible name
"Util" and indicates selection through semantics and color. Mode switches
use [retained stacks](screen-stack.md#independent-mode-stacks) and leave
controller execution unchanged.

Util provides the live Terminal and controller connection settings. Program
provides the welcome screen and an in-memory editor/file explorer, Manual
provides a details screen with local notes, and MDI provides a local text
buffer. See the guides for [planned machine operations](machine-operations.md)
and [program handling](programs-macros-tools.md).

## System guides

- [Screen navigation, mode stacks and softkeys](screen-stack.md)
- [Shared workspace dialogs](dialogs.md)
- [Workspace grid sizing](layout-grid-unit.md)
- [CNC and text touch keypads](keypads.md)
- [Controller connection and settings](controller-connection.md)
- [Util Terminal and traffic diagnostics](terminal.md)

See [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/)
for the desktop UI framework.
