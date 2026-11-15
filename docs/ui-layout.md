# Desktop UI layout

GRBL Commander uses a persistent operator-interface shell implemented with
Compose Multiplatform Desktop.

## Current implementation

The shell is composed of four regions, matching the static HTML/CSS and
screenshot design references:

| Region          | Current responsibility                                                                                                 |
|-----------------|------------------------------------------------------------------------------------------------------------------------|
| Header          | Image logo, application identity, Program/Manual/MDI/Util UI-mode selection, current context and explicit connection status |
| Main workspace  | ScreenStackHost: retains all mode screen stacks and renders only the active stack                                      |
| Bottom softkeys | Eight fixed slots, F1 through F8                                                                                       |
| Right softkeys  | Seven fixed slots, R1 through R7                                                                                       |

The header spans the entire screen width. The right-side softkeys span the
workspace and bottom-softkey row. The main workspace and bottom keys share the
remaining width. The initial default sizes are 78 dp for the header, 94 dp for
the bottom keys and 142 dp for the right column; they may be adjusted for screen
sizes and touch targets.

`AppShell` owns the persistent regions, accepts a Compose content slot for the
workspace, and exposes an optional header-controls slot for UI-mode selection.
`ShellState` contains display-only header information, including connection health
observed from the shared application-owned `ControllerConnection`. The application
composition root creates one core-owned connection/settings pair. All modes
receive that same connection owner through `LocalControllerConnection`; it outlives their
stacks and compositions. Util supplies controller connection settings; a terminal
command UI is not implemented. Screen instances own
softkey bindings; the shell reads them from the selected persistent stack. The
Program, Manual, MDI and icon-only ⚙ Util buttons change presentation context
only. Util connection actions use the shared owner; other mode examples are
in-memory fixtures. No UI exposes motion commands.

The application icon and header logo use the Compose resource
`drawable/grbl_commander_icon.png` (384 × 256), while the workspace uses
`drawable/grbl_commander_splash.jpg` (1024 × 768). Compose generates typed
resource accessors for these bundled images. Load them through the generated
`Res.drawable` accessors and `org.jetbrains.compose.resources.painterResource`;
do not load application images through the deprecated classpath string API.
The icon is an optimized RGBA PNG derived from the supplied transparent
artwork, preserving transparent corners for the window icon and header. The
splash image remains an optimized JPEG derived from the supplied artwork.
Images preserve their original proportions with `ContentScale.Fit` rather than
stretching or cropping. The operating system displays the application icon
according to its window-manager conventions.

## Screen navigation

`NavigationParent` exposes an owning `screenStack`. Both `ScreenStack` and
`Screen<R>` implement this interface. `ScreenStack(root)` binds the root after
the screen has been constructed; access to `root.screenStack` before binding
fails. The root cannot be removed. An opened screen is bound to exactly one
stack
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
that instance starts a fresh composition while its instance-owned state
survives.

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

Visual overlay/input behavior does not determine softkey inheritance. The active
stack resolves each softkey bank from its retained screen instances; shell
shortcuts and pointer input operate only on that active stack. Inactive stacks
retain their own keys and screen state without receiving actions.

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
property changes do not schedule Compose recomposition and must not be used to
change softkeys; otherwise the displayed definition can remain stale. Modifiers
should also be stable screen-owned instances; replace an observable override
when their composition changes.
`Screen<R>` independently declares `inheritBottomKeys` and
`inheritRightKeys`, both false by default. Each bank walks from the top
down to the first screen that does not inherit that bank, or to the root.
From that boundary, definitions replace bindings and modifiers transform
existing definitions in root-to-top order. A modifier with no existing
definition is a no-op. An explicit `SoftKeyDefinition.Blank` replaces an
inherited action with a blank, non-actionable key. Undefined slots are also
blank. A screen may inherit only one bank; a fullscreen screen and an overlay
follow the same rules.

`ScreenStack.softKeyDefinition(slot)` reads the current effective binding when
an action is activated. `AppShell` renders from `effectiveBottomKeys` and
`effectiveRightKeys`, which are read-only `derivedStateOf` maps for each bank.
These memoize structural composition until navigation or observable overrides
change; predicate updates alone leave the composed definitions intact. Since
rendering and activation use the same effective binding, structural softkey
updates must be observable to Compose. Neither the stack nor shell keeps a
separate mutable copy.
Screen-owned callbacks and predicate closures remain attached to their
original screen unless a modifier explicitly replaces them. Pushing,
popping and mode selection select the current effective bindings without
saving/restoring any callback state.
`AppShell` uses the same `SoftkeyTile` for both banks, preserving all eight
bottom
and seven right positions and their dimensions. Blank tiles contain no text or
icon, including shortcut hints. Disabled labelled tiles retain label, icon and
hint; active tiles use an accent outline/background and selected semantics.
Compose evaluates enabled/active predicates during composition, observing state
reads even when a modifier has retained the predicates from another definition.

Pointer and keyboard input both call `activateSoftKey(currentStack, slot)`.
It resolves the currently active stack's effective definition at activation
time, then checks its action and latest `enabled` state. It never stores a
callback or compares a captured binding identity, and a callback is free to
navigate during its own execution. `active` does not authorize an action.
For pointer gestures `SoftkeyTile` uses Compose's
`key(stack, entry, definition)`:
the selected stack, existing top navigation entry and effective definition
determine the clickable composition's lifetime. On a visible navigation,
mode or definition change Compose disposes the old gesture so releasing it
does not click the new action. Reopening the same screen produces a fresh
navigation entry. The effective definition uses its ordinary data-class
equality, so a modifier-produced equivalent `copy()` does not churn the
gesture key. Predicate-result changes only update the rendered presentation.
No independent softkey lifecycle or identity model is kept. All navigation
and activation run on the UI thread.

F1–F8 activate the bottom bank. Shift+F1–F7 activate R1–R7; Shift+F8 and
shortcuts
with Ctrl, Alt or Meta are unmapped. The shell handles mapped key-down events
and
consumes their key-up events, even for blank/disabled slots, before workspace
children can process them. Every key-down, including OS key-repeat, triggers
activation; repeated actions require appropriate preconditions in their owners.
Future physical-panel input can call the same slot activation function.
Hardware-panel integration is not implemented.

Inherited definitions remain reactive: Compose observes `enabled` and `active`
predicates during tile composition and reads observable screen key maps when
resolving slots. Changing an inherited observable override or navigating
changes the effective definition; changes to predicate results do not require
rebuilding definitions. An inactive mode's stack can be inspected but cannot
receive events through the shell's current-stack activation path.

### Screen-owned typed results

The editor's file selection uses the actual `FileExplorer` screen, without a
facade, navigator, CompositionLocal, or generic result casts:

```kotlin
class FileExplorer(initialDirectory: String) :
    Screen<FileExplorerResult>(FileExplorerResult.Cancelled) {
    @Composable
    override fun Content() { /* file selection UI */
    }

    fun selectFile(filename: String) {
        finish(FileExplorerResult.Selected(filename))
    }
}

// Inside Editor:
FileExplorer("/programs").open(this@Editor) { result ->
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

In **Program** mode, choose **Open navigation example** on the welcome screen,
edit the draft, then
choose **Open example file**. Each invocation creates a `FileExplorer` screen
instance. **Cancel** or `pop()` restores the same editor and leaves its draft
unchanged. Clicking a fixture selects it without closing the explorer. **Select
file** or **F1 Select** delivers `Selected(filename)`; the editor looks up the
in-memory fixture and updates its filename and draft. **Back to
welcome** completes the editor's `Unit` result. The welcome screen's F1 opens
the editor; the editor's F1 opens a file explorer
and F8 returns to welcome. Explorer F1 Select and F2 Clear selection enable only
when a fixture is selected; F8 cancels. Explorer replaces the entire bottom bank
but inherits the editor's right bank. In the in-memory example, the editor also
offers Open file (R1) and Back (R2). While the explorer is open, its R1 modifier
relabels and disables the inherited editor Open file action, R2 explicitly
blanks the inherited Back action, and R7 Info (Shift+F7) toggles its active
presentation and fixture information. Closing the explorer restores the
editor's definitions automatically. These are stable per-instance definitions
with bound callbacks and snapshot-state predicates. The example has no real
local
or controller filesystem access and cannot execute G-code.

While the explorer is open, use the header to select **Manual**, open **Manual
details**, and optionally type local notes. Return to **Program**: the same
explorer, editor instance and draft are available; dismiss or select a file
to deliver the typed result. The Manual details screen remains open when
switching back. **MDI** contains an unsent, local text buffer. The examples are
deliberately disconnected from any controller. They neither simulate jogs nor
expose motion operations.

## Architectural requirements

As the UI grows, modes own their workspace content and contextual actions, not
the global layout. Shared softkey components support labels, icons,
disabled/active state and typed slot activation with screen-owned callbacks. The
action dispatch layer must remain separate from GRBL transports and physical
execution.

Machine position/DRO, machine operation pages, terminal command UI,
programmable actions, and physical CNC operations are planned and are not part
of the desktop demonstration.

### Util connection settings

Util is selected by an icon-only ⚙ header button with accessible name "Util" and
selected semantics/color. Connection health stays in the separate display-only
header indicator, with the current endpoint in the header context. No settings
screen is pushed onto Program, Manual or MDI stacks.

Util owns a persistent `ScreenStack(UtilMode(...))`. Its initial root is an empty
workspace containing **Controller connection**; the terminal root is planned in
[#22](https://github.com/skasti/grbl-commander/issues/22). F7 **⌨ Util** selects the
root; F8 **🔗 Connection** opens `ControllerConnectionSettings` above it using the
root as explicit parent. F6 is unassigned. F8 while settings is active cannot
create another screen.
These slots show selected view, independently of actual controller health. Closing
settings returns to the same root. Each screen owns its banks; settings does not
inherit the right bank, so future terminal filters are covered automatically.

Settings is a scrolling touch-friendly form with Serial/USB and TCP/network
selection. TCP requires host/IP and port 1–65535; serial supports optional refresh,
descriptive port buttons and manual device entry. Baud is always visible, while
Advanced exposes bits, parity, stop bits, flow control and DTR/RTS. Manual entry
remains enabled when discovery is empty or fails. Defaults are 115200, 8N1, no
flow control, DTR/RTS false. Edits never change the current endpoint/session.

F1 **Save**, F2 **Connect**, F3 **Disconnect**, F7 **Util** and F8 **Connection**
use the existing guarded softkey activation. Close remains a workspace button;
there is no separate Close softkey. Matching workspace actions
use the same screen callbacks. Invalid values show validation errors before any
transport is created. Save persists without connecting; Connect awaits the status
handshake before saving and reporting success. Progress/errors, current endpoint,
last connection failure and stale/unknown report warning reflect shared owner
state. Staleness expires after three seconds without reports and is also reflected
in the header. Core owns background status polling every 500 ms after handshake,
independently of the active screen/mode; UI does not start additional pollers.
Polling stops on connection disposal/failure and pauses during reset handshake.
Missing replies still become stale; sending a poll is not a confirmed report.
Deliberate Disconnect displays neutral progress/status, with no technical
"Session closed" error or stale-state warning. Disconnected values remain labelled
as last observations, and a neutral note explains that they are not being updated.
Connecting displays a waiting note; actual failure and stale reports while
connected retain warning/error styling. The header adds a stale qualifier only
while connected, not during expected disconnect/shutdown.

Replacing/disconnecting an active or connecting session requires a screen-local
confirmation describing interruption and uncertain pending command outcomes. It
never resets/stops/replays machine work implicitly. Disconnect can interrupt a
handshake. Disconnect is disabled during Save and a running Disconnect; repeated
callbacks cannot start competing operations. A confirmed Disconnect reserves the
single operation slot before awaiting Connect cancellation and cleanup. If the
handshake has completed, it waits for Connect's persistence instead of cancelling
that save. Workspace buttons and softkeys use the same enabled condition.
Close cancels unfinished form work but keeps a completed connection.
Switching modes retains the form, its current operation and scroll state. Saving
and discovery failures remain visible without claiming false connection health.

Presentation depends on `:core`. `ControllerSettings` owns the saved configuration
and load/validation/save/connect workflows; the screen owns only its editable
draft, progress, confirmations and navigation. It requests Save or Connect, and
core performs any persistence internal to those operations. Neither Compose nor
screens instantiate or refer to the JVM Preferences adapter. Serial discovery is
requested through core and executed by infrastructure. `Main` supplies the shared
core services to `CommanderApp`; changing mode never recreates them.

See [architecture.md](architecture.md), especially sections 2, 5 and 10, and the
repository [AGENTS.md](../AGENTS.md).

