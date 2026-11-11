# Desktop UI layout

GRBL Commander uses a persistent operator-interface shell implemented with Compose Multiplatform Desktop.

## Current implementation

The shell is composed of four regions, matching the static HTML/CSS and screenshot design references:

| Region | Current responsibility |
| --- | --- |
| Header | Image logo, application identity, Program/Manual/MDI UI-mode selection, current context and explicit connection status |
| Main workspace | ScreenStackHost: retains all mode screen stacks and renders only the active stack |
| Bottom softkeys | Eight fixed slots, F1 through F8 |
| Right softkeys | Seven fixed slots, R1 through R7 |

The header spans the entire screen width. The right-side softkeys span the workspace and bottom-softkey row. The main workspace and bottom keys share the remaining width. The initial default sizes are 78 dp for the header, 94 dp for the bottom keys and 142 dp for the right column; they may be adjusted for screen sizes and touch targets.

`AppShell` owns the persistent regions, accepts a Compose content slot for the workspace, and exposes an optional header-controls slot for UI-mode selection. `ShellState` contains display-only header information. Screen instances own softkey bindings; the shell reads them from the selected persistent stack. The Program, Manual and MDI buttons change presentation context only. Softkeys invoke safe, in-memory UI actions; machine commands are not implemented.

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

Only the top screen of the active mode receives workspace pointer and keyboard
input. Full-workspace pointer targets prevent clicks in overlay margins from
reaching lower siblings; covered layers also consume pointer events before their
children. Covered focus groups reject focus entry and key preview handlers block
stale focused descendants. Covered content is removed from accessibility
semantics. Focus returns to the active layer, not necessarily the previously
focused editor field. The header and softkeys stay outside this input boundary.

### Independent mode stacks

`CommanderApp` creates a stable `Map<OperatorMode, ScreenStack>` with
`remember`, one stack per mode per application window. It keeps `activeMode`
in ordinary Compose state. The selected mode controls the header and selects
`activeStack` for the workspace; switching it never pops, finishes or
reconstructs another mode's stack. Each stack independently owns its root,
open screens, overlays and typed callbacks. There is no global navigator or
implicit redirection to the visible stack: `screen.open(parent)` resolves
the bound stack and `screen.open(stack)` uses the explicit stack even if
it is inactive.

`ScreenStackHost(stacks, activeStack)` composes all supplied stacks using
stable stack identities and reuses the single-stack rendering/input logic.
Only the active stack is drawn; inactive stacks remain in the composition,
are removed from accessibility semantics, reject pointer/keyboard input and
cannot enter or steal focus. Reactivation focuses the current top screen after
clearing focus from the previous stack. Screen instances, nested overlay
compositions and ordinary non-saveable Compose `remember` state survive mode
changes. Popping an overlay disposes only that overlay; closing the window
disposes its stacks and compositions. State is not persisted across restarts.

Visual overlay/input behavior does not depend on softkey inheritance.
Softkey selection uses only the active stack's top screen. Workspace overlay
input boundaries do not block shell shortcuts. Cross-screen softkey inheritance
and modifier resolution remain planned in issue #4.

### Screen-owned softkey contract

`Screen<R>` owns stable `bottomKeys` and `rightKeys` maps keyed by
`BottomSoftKeySlot` (F1–F8) and `RightSoftKeySlot` (R1–R7). Values implement
`SoftKeyOverride`: immutable `SoftKeyDefinition` values hold a label, optional
Compose `ImageVector`, nullable bound action, and enabled/active predicates
(default true/false). `SoftKeyDefinition.Blank` has no label, icon or action
and is disabled. There is no visibility flag. Disabled labelled definitions
retain their content. `SoftKeyModifier` transforms a definition, typically with
`copy`, preserving callbacks and predicates unless explicitly replaced.
Screens should create definitions once and let predicates read instance state.
Ordinary predicate changes recompose the tiles without replacing their bindings
or reconstructing their maps. When a definition/override genuinely changes,
use a `mutableStateMapOf` for individual slot updates or return a map from a
`mutableStateOf(mapOf(...))`-backed property. Ordinary mutable maps or non-state
property changes do not schedule Compose recomposition. Modifiers should also
be stable screen-owned instances; replace an observable override when their
composition changes.
`ScreenStack.softKeyBinding(slot)` selects complete definitions from the current
top screen. Undefined slots and modifier-only overrides render as blank because
cross-screen composition belongs to issue #4. No lower screen actions are exposed.
`AppShell` uses the same `SoftkeyTile` for both banks, preserving all eight bottom
and seven right positions and their dimensions. Blank tiles contain no text or
icon, including shortcut hints. Disabled labelled tiles retain label, icon and
hint; active tiles use an accent outline/background and selected semantics.
Compose evaluates enabled/active predicates during composition, observing state
reads even when a modifier has retained the predicates from another definition.

Pointer and keyboard input both call `activateSoftKey(currentStack, slot, ...)`.
The function re-reads the selected stack and current binding, checks action and
enabled state, and verifies binding identity again before invocation. Pointer
callbacks carry the displayed binding's stack, originating entry, original
definition identity and modifier provenance. The effective definition is compared
by fields rather than reference: equivalent `copy()` results keep logical
identity, while changed labels, actions, predicates, source overrides or modifier
composition invalidate pending gestures. A modifier's provenance includes the
navigation entry where it is declared so reattaching a screen does not revive
an old binding. Replacement, removal and pop/reopen invalidate pending callbacks.
Binding changes also dispose pending tile gestures. Observable predicate-result
changes only recompose presentation; they do not replace binding identity.
`enabled` is checked at invocation even if the tile was previously enabled,
and `active` does not authorize an action. All navigation and
activation run on the UI thread.

F1–F8 activate the bottom bank. Shift+F1–F7 activate R1–R7; Shift+F8 and shortcuts
with Ctrl, Alt or Meta are unmapped. The shell handles mapped key-down events and
consumes their key-up events, even for blank/disabled slots, before workspace
children can process them. Future physical-panel input can call the same slot
activation function. Hardware-panel integration is not implemented.

Issue #4 will resolve each bank independently across retained screen entries,
using separate inheritance flags (default false), definitions that replace and
modifiers that transform existing definitions bottom-to-top. Modifiers on missing
slots will do nothing. That traversal/composition is not implemented here.

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

In **Program** mode, choose **Open navigation example** on the welcome screen, edit the draft, then
choose **Open example file**. Each invocation creates a `FileExplorer` screen
instance. **Cancel** or `pop()` restores the same editor and leaves its draft
unchanged. Clicking a fixture selects it without closing the explorer. **Select file** or
**F1 Select** delivers `Selected(filename)`; the editor looks up the in-memory fixture and updates its filename and draft. **Back to
welcome** completes the editor's `Unit` result. The welcome screen's F1 opens the editor; the editor's F1 opens a file explorer
and F8 returns to welcome. Explorer F1 Select and F2 Clear selection enable only
when a fixture is selected; F8 cancels. R7 Info (Shift+F7) toggles its active
presentation and fixture information. These are stable per-instance definitions
with bound callbacks and snapshot-state predicates. The example has no real local
or controller filesystem access and cannot execute G-code.

While the explorer is open, use the header to select **Manual**, open **Manual
details**, and optionally type local notes. Return to **Program**: the same
explorer, editor instance and draft are available; dismiss or select a file
to deliver the typed result. The Manual details screen remains open when
switching back. **MDI** contains an unsent, local text buffer. The examples are
deliberately disconnected from any controller. They neither simulate jogs nor
expose motion operations.

## Architectural requirements

As the UI grows, modes own their workspace content and contextual actions, not the global layout. Shared softkey components support labels, icons, disabled/active state and typed slot activation with screen-owned callbacks. The action dispatch layer must remain separate from GRBL transports and physical execution.

Machine position/DRO, functional mode-specific pages, live connection state, programmable actions, and physical CNC operations are planned and are not part of the desktop demonstration.

See [architecture.md](architecture.md), especially sections 2, 5 and 10, and the repository [AGENTS.md](../AGENTS.md).
