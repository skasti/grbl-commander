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

`NavigationParent` exposes an owning `screenStack`. Both `ScreenStack` and
`Screen<R>` implement this interface. `ScreenStack(root)` binds the root after
the screen has been constructed; access to `root.screenStack` before binding
fails. The root cannot be removed. An opened screen is bound to exactly one stack
until dismissed, and cannot simultaneously belong to multiple stacks. Its
`screenStack` becomes unavailable on removal.

`Screen<R>` is one screen instance with its own `Content()`, state, navigation
methods and typed result. The constructor receives its cancellation result. For
screens without meaningful results use `Screen<Unit>(Unit)`; no extra result
class is needed. A child opens with `MyScreen(...).open(this@ParentScreen) {
result -> ... }`. The parent must be bound and must be the active screen.
Covered screens cannot open new overlays or complete their own results.
A screen can call protected `finish(result)` only while it is topmost.
The stack removes the screen, detaches its binding and then delivers the typed
callback at most once. Stale and duplicate completion attempts return false.

`ScreenStack` owns screen **instances**, ordered root to top. Its Compose
snapshot state makes `top`, `size` and the read-only `screens` observable.
Call navigation on the UI thread. `push(screen)` and typed `open(screen,
onResult)` add an instance above the current top. Passing a `ScreenStack` as
the parent in `MyScreen().open(stack) { ... }` does the same, even if another
screen is already displayed; this is an intentional direct-stack action rather
than navigation initiated by a covered screen. `pop()` returns the removed
instance, or null at the root. `dismiss(screen)` only removes that exact
topmost non-root instance. Both ordinary removal methods deliver the screen's
cancellation result before returning. Screens are compared by identity even if
their `equals` methods agree. Different stacks have independent binding,
results, and UI histories.

`ScreenStackHost` stays inside the persistent shell workspace. It renders
`Content()` without injecting navigation dependencies. Every entry remains
composed, keyed by an identity entry, in root-to-top drawing order. Each overlay
fills the workspace input region and draws a dark scrim above the preceding
screen; its visible content may occupy only part of that region. Covered screens
retain their instance, callbacks, ordinary Compose `remember` state, and
composition effects. Popping disposes only the removed entry; a later push of
that instance starts a fresh composition while its instance-owned state survives.

Only the top screen receives workspace pointer and keyboard input. Full-workspace
pointer targets prevent clicks in overlay margins from reaching lower siblings;
covered layers also consume pointer events before their children. Covered focus
groups reject focus entry, key preview handlers block stale focused descendants,
and navigation clears captured focus and focuses the active layer. Covered
content is removed from the accessibility semantics tree. Focus returns to the
active layer, not necessarily the previously focused editor field. The header
and softkeys stay outside this input boundary.

Visual overlay/input behavior does not depend on softkey inheritance.
Screen-owned softkey definitions, inheritance, global shortcut dispatch and
mode switching are planned separately. The current shell softkeys remain
noninteractive.

### Screen-owned typed results

The editor's file selection uses the actual `FileExplorer` screen, without a
facade, navigator, CompositionLocal, or generic result casts:

```kotlin
class FileExplorer(initialDirectory: String) :
    Screen<FileExplorerResult>(FileExplorerResult.Cancelled) {
    @Composable override fun Content() { /* file selection UI */ }

    fun selectFile(filename: String) {
        finish(FileExplorerResult.Selected(filename))
    }
}

// Inside EditorScreen:
FileExplorer("/programs").open(this@EditorScreen) { result ->
    when (result) {
        is FileExplorerResult.Selected -> loadExampleFile(result.filename)
        FileExplorerResult.Cancelled -> Unit
    }
}

// A host may also open directly on its own stack:
FileExplorer("/programs").open(programStack) { result -> /* ... */ }
```

The concrete example screen and its files are in-memory UI fixtures. Opening
through a screen automatically resolves that screen's bound stack. Direct-stack
opening uses the given stack. A detached or covered screen cannot be an active
navigation parent. Typed completion restores the parent screen before invoking
its callback; cancelling explicitly, pressing back or calling `pop()` delivers
the same `Cancelled` result.

### Runnable example

Choose **Open navigation example** on the welcome screen, edit the draft, then
choose **Open example file**. Each invocation creates a `FileExplorer` screen
instance. **Cancel** or `pop()` restores the same editor and leaves its draft
unchanged. Selecting a fixture delivers `Selected(filename)`; the editor
looks up the in-memory fixture and updates its filename and draft. **Back to
welcome** completes the editor's `Unit` result. The example has no real local
or controller filesystem access and cannot execute G-code.

The startup screen is deliberately disconnected from any controller. It does
not simulate a real connected machine or expose motion operations.

## Architectural requirements

As the UI grows, modes own their workspace content and contextual actions, not the global layout. Shared softkey components must support labels, icons, disabled state and typed actions when those capabilities are implemented. The action dispatch layer must remain separate from GRBL transports and physical execution.

Machine position/DRO, mode-specific pages, live connection state, programmable actions, and physical CNC operations are planned and are not part of the startup shell.

See [architecture.md](architecture.md), especially sections 2, 5 and 10, and the repository [AGENTS.md](../AGENTS.md).

