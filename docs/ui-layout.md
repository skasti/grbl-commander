# Desktop UI layout

GRBL Commander uses a persistent operator-interface shell implemented with Compose Multiplatform Desktop.

## Current implementation

The shell is composed of four regions, matching the static HTML/CSS and screenshot design references:

| Region | Current responsibility |
| --- | --- |
| Header | Image logo, application identity, current context and explicit connection status |
| Main workspace | ScreenStackHost: welcome image and optional navigation example overlays |
| Bottom softkeys | Eight fixed slots, F1 through F8 |
| Right softkeys | Seven fixed slots, R1 through R7 |

The header spans the entire screen width. The right-side softkeys span the workspace and bottom-softkey row. The main workspace and bottom keys share the remaining width. The initial default sizes are 78 dp for the header, 94 dp for the bottom keys and 142 dp for the right column; they may be adjusted for screen sizes and touch targets.

`AppShell` owns the persistent regions and accepts a Compose content slot for the workspace. `ShellState` contains display-only header information and softkey labels. All softkeys are noninteractive placeholders, not machine commands.

The application icon and header logo use the classpath resource `images/grbl-commander-icon.png` (384 × 256), while the workspace uses `images/grbl-commander-splash.jpg` (1024 × 768). The icon is an optimized RGBA PNG derived from the supplied transparent artwork, preserving transparent corners for the window icon and header. The splash image remains an optimized JPEG derived from the supplied artwork. Images preserve their original proportions with `ContentScale.Fit` rather than stretching or cropping. The operating system displays the application icon according to its window-manager conventions.

## Screen navigation

`Screen` is a presentation contract with `Content(stack)`. `ScreenStack(root)` owns
screen **instances**, ordered from root to top. Its Compose snapshot state makes
`top`, `size`, and the read-only `screens` snapshot observable. Call navigation
methods on the UI thread. `push(screen)` rejects an instance already in the stack;
different instances remain distinct even if they compare equal. `pop()` returns the
removed instance, or null at the root. The stack never becomes empty.
`dismiss(screen)` only removes that exact instance if it is topmost and not root.
Result-bearing entries deliver their typed cancellation value on `pop()` or
`dismiss(screen)`. Explicit completion removes the top entry before invoking its
typed callback. Callbacks are delivered at most once, and stale or covered
instances cannot finish another overlay.

`ScreenStackHost` stays inside the shell workspace. Every entry remains composed,
keyed by an identity entry, in root-to-top drawing order. Each overlay fills the
workspace input region and draws a dark scrim above the preceding screen. Its own
content can occupy only part of that region. Covered screens retain their instance,
callbacks, ordinary `remember` state, and composition effects. Popping disposes only
the removed entry; a later push of that instance starts a fresh composition while
its instance-owned state survives. Screens must not treat composition lifetime as
permission to receive operator input or perform machine operations.

Only the top screen receives workspace pointer and keyboard input. Full-workspace
pointer targets prevent clicks in overlay margins from reaching lower siblings;
covered layers also consume pointer events before their children. Covered focus
groups reject focus entry, key preview handlers block stale focused descendants,
and navigation clears captured focus and focuses the active layer. Covered content
is removed from the accessibility semantics tree. Focus returns to the active layer,
not necessarily to the previously focused editor field. The shell lies outside this
input boundary and is not duplicated by screens.

Visual overlay/input behavior has no dependency on softkey inheritance. Screen-owned
softkey definitions, inheritance, global shortcut dispatch and mode switching are
planned separately. The existing shell softkeys remain noninteractive.

### Screen-specific result API

`ScreenStackHost` provides a `ScreenNavigationContext` for each composed screen
entry through a private Compose CompositionLocal. `rememberFileExplorer()`
returns a `FileExplorer` facade bound to that entry and host:

```kotlin
val fileExplorer = rememberFileExplorer()
Button(onClick = {
    fileExplorer.open(initialDirectory = "/programs") { result ->
        when (result) {
            is FileExplorerResult.Selected -> loadExampleFile(result.filename)
            FileExplorerResult.Cancelled -> Unit
        }
    }
}) { Text("Open example file") }
```

The facade takes screen-specific parameters and delivers
`FileExplorerResult.Selected(filename)` or `Cancelled`; callers do not
pass a stack or cast a generic result. An unbound `FileExplorerScreen` cannot
discover a window and must be opened through the facade. Navigation contexts
belong to one entry in one host, expire when that composition is removed, and
cannot open an overlay while their entry is covered. Nested overlays get their
own contexts; independent windows do not share a singleton navigator.

The stack owns callback delivery, entry identity, at-most-once completion and
cancellation on normal back/pop. It restores the underlying entry *before*
delivering the callback. Covered compositions remain alive.

### Runnable example

Choose **Open navigation example** on the welcome screen, edit the draft, then
choose **Open example file**. Each invocation creates a `FileExplorerScreen` instance.
**Cancel**, back, or `pop()` dismisses the explorer and delivers
`FileExplorerResult.Cancelled` to the same editor callback, leaving the draft
unchanged. Selecting an example file delivers `Selected(filename)`; the editor
looks up the in-memory fixture and updates its filename and draft. **Back to welcome** dismisses
the editor. Files are in-memory fixtures, not local/controller filesystem access, and
this example cannot execute G-code. Explicit cancellation and generic `pop()` produce the same typed cancellation result.

The startup screen is deliberately disconnected from any controller. It does not simulate a real connected machine or expose motion operations.

## Architectural requirements

As the UI grows, modes own their workspace content and contextual actions, not the global layout. Shared softkey components must support labels, icons, disabled state and typed actions when those capabilities are implemented. The action dispatch layer must remain separate from GRBL transports and physical execution.

Machine position/DRO, mode-specific pages, live connection state, programmable actions, and physical CNC operations are planned and are not part of the startup shell.

See [architecture.md](architecture.md), especially sections 2, 5 and 10, and the repository [AGENTS.md](../AGENTS.md).

