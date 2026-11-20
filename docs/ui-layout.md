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
sizes and touch targets. The supported layout starts at 1024 × 720 pixels
at 1× density. See the [Compose UI screenshots](../README.md#compose-ui-screenshots)
for fixed CI capture resolutions, local verification and published views.
PR review comments compare captures with the main baseline and also include
captures from modified tests. Each scenario shows one expanded resolution;
additional sizes are collapsed. Capture checkpoints remain in tests, without a
per-screen workflow list or generated documentation gallery.

`AppShell` owns the persistent regions, accepts a Compose content slot for the
workspace, and exposes an optional header-controls slot for UI-mode selection.
`ShellState` contains display-only header information, including connection health
observed from the shared application-owned `ControllerConnection`. The application
composition root creates one core-owned connection/settings pair. All modes
receive that same connection owner through `LocalControllerConnection`; it outlives their
stacks and compositions. Util supplies the live Terminal and controller connection settings. Screen instances own
softkey bindings; the shell reads them from the selected persistent stack. The
Program, Manual, MDI and icon-only ⚙ Util buttons change presentation context
only. Util connection actions use the shared owner; other mode examples are
in-memory fixtures. Terminal accepts explicit manual commands and guarded realtime controls.

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
input. Full-workspace background pointer targets behind screen content prevent clicks
in overlay margins from reaching lower siblings. The active screen's child
gestures remain unconsumed by the host, including small pointer movements while
a key is held; covered layers consume pointer events in the Initial pass before
their children. Covered focus groups reject focus entry and key preview handlers block
stale focused descendants. Covered content is removed from accessibility
semantics. Focus returns to the active layer, not necessarily the previously
focused editor field. The header and softkeys stay outside this input boundary.

### Shared modal dialogs

`ui.components.dialogs.BaseDialog` is a Compose layout for a centered surface
inside the workspace. It supplies consistent sizing, padding, and styling,
and scrolls the title and body together when space is limited. Callers supply
the title and a composable body slot.

`ui.components.dialogs.ModalDialog<R>` uses `BaseDialog` to display a message
and provides labelled bottom-softkey choices carrying typed result values.
Open this `Screen<R>` with an explicit parent and handle the result after the
parent has been restored:

```kotlin
ModalDialog(
    title = "Interrupt current connection?",
    message = "Disconnect the current controller session?",
    cancellationResult = false,
    choices = mapOf(
        BottomSoftKeySlot.F1 to ModalDialog.Choice("Disconnect", true),
        BottomSoftKeySlot.F8 to ModalDialog.Choice("Cancel", false),
    ),
).open(this@ParentScreen) { confirmed ->
    if (confirmed) requestDisconnect()
}
```

The persistent bottom softkeys supply choices through the existing pointer and
keyboard activation path. The caller supplies labels, slots and result values.

Both key banks replace inherited bindings. Unassigned slots, including all right
keys, are blank, so covered form actions and Util navigation cannot leak through.
`ScreenStackHost` supplies the scrim, focus/input isolation and retained parent
composition. `Screen`/`ScreenStack` enforce topmost-only completion and at-most-once
result delivery; pop/dismiss returns the supplied cancellation result. Mode
switching retains a pending modal on its original stack without confirming it.

Connection interruption uses F1 **Disconnect** / **Disconnect and connect** and
F8 **Cancel**. Repeating the initiating F2/F3 cannot confirm or duplicate the
modal, because those slots are blank while it is topmost. Cancelling restores the
same form, draft and navigation/softkey state. The parent screen applies the
confirmed result through its existing core operation.

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
children can process them. Definitions default to repeatable key-down activation. `repeatable = false`
consumes repeated key-downs until physical release; Terminal realtime actions use
this option. The shell remembers physical keys across navigation, preventing held
keys from activating a replacement binding. Owners also guard in-flight requests.
Future physical-panel input can call the same slot activation function.
Hardware-panel integration is not implemented.

Inherited definitions remain reactive: Compose observes `enabled` and `active`
predicates during tile composition and reads observable screen key maps when
resolving slots. Changing an inherited observable override or navigating
changes the effective definition; changes to predicate results do not require
rebuilding definitions. An inactive mode's stack can be inspected but cannot
receive events through the shell's current-stack activation path.

### Softkey pages

`SoftKeyPages<S>` holds the selected page for one screen-owned bank. Screens expose
its observable `keys` through their existing `bottomKeys` or `rightKeys` getter;
stack resolution, inheritance and guarded activation remain unchanged. Common keys
keep their slots and reactive definitions on every page. The reserved Next page
slot (F8 for the bottom bank, R7 for the right bank) shows the current page count
and wraps from the last page to the first. A single page has no paging button.
Next page ignores keyboard repeat. Each screen and bank retains independent page
state; covered or inactive screens never contribute actions without inheritance.

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

Machine position/DRO, machine operation pages,
programmable actions, and physical CNC operations are planned and are not part
of the desktop demonstration.

### Util connection settings

Util is selected by an icon-only ⚙ header button with accessible name "Util" and
selected semantics/color. Connection health stays in the separate display-only
header indicator, with the current endpoint in the header context. No settings
screen is pushed onto Program, Manual or MDI stacks.

The application retains one Util `ScreenStack(Terminal(...))` per window.
Terminal is visible on first entry. Its R7 **🔗 Connection** opens
`mode.utils.connection.ControllerConnectionSettings` above it with an explicit
parent. The form replaces this key with R7 **⌨ Terminal**, which
returns to the same root state. These are navigation actions labelled for their
destination, not selected-screen or connection-health indicators. Terminal has no
self-selection key. Switching modes restores an open settings screen. Settings
replaces both banks and never inherits Terminal filters or realtime actions.

Startup uses the saved valid endpoint once after UI readiness. The connection
attempt is asynchronous and cannot navigate, open a modal or toast, or show a
workspace error. Failure changes the existing header connection indicator;
Connection settings expose the retained core failure details. Manual actions
supersede pending startup, and no automatic retry occurs.

### Terminal console

The workspace has compact endpoint/controller status, a dark virtualized monospaced
output region and an editable `>` prompt inside the same console surface. Output
entries distinguish TX, RX, category,
timestamps and failed/uncertain handoffs. Original unfamiliar text is retained;
control characters are escaped. Selection and copying are supported within one
output entry; selection spanning multiple lazy entries is not implemented.

Incoming events never replace the prompt value, caret or focus. Ordinary
text editing, selection and paste use Compose text input. Enter or the onscreen
keyboard Done/Enter action is the only submission action; held Enter and concurrent
IME/key submission cannot duplicate a pending request. Blank, multiline, overlong,
non-ASCII and embedded realtime input is rejected. Local admission/failure feedback
appears beneath the prompt, and failed submission preserves it. Success clears the
unchanged line after acknowledgment, without claiming motion completion. Up/Down
and F3/F4 on the first bottom-softkey page browse up to 100 recent transport handoffs
and restore the unsent draft. Recall is updated before acknowledgment, retains
controller errors/timeouts/uncertainty and removes exact local transport rejections. Nothing persists command history.

F7 **Filters**, with a funnel icon, replaces the current bottom bank with a
contextual filter menu. F7 **↩ Back** restores the previously selected bottom
page. Paging is unavailable while filtering; R7 Connection remains available.
Input, scrollback and filter values are unchanged. There is no Show all action
and no filter buttons in the workspace.

| Bottom slot in Filters | Filter/action | Initially visible |
| --- | --- | --- |
| F1 | Status RX | No |
| F2 | Poll TX | No |
| F3 | OK | Yes |
| F4 | Info and startup | Yes |
| F5 | Errors and alarms | Yes |
| F6 | Time | Yes |
| F7 | Back | Restores the selected ordinary page |
| F8 | Unassigned | No paging behind the menu |

Active filters mean visible. Normal/realtime commands always remain visible.
Filtering is category-based at receipt and affects neither session processing nor
shared alarm/connection indicators. The observable terminal ring retains up to
10,000 admitted events; hidden events consume no capacity. Changing filters keeps
existing rows and never reconstructs older hidden traffic. Subscription continues
while other modes or Connection settings are displayed. Clear view records a local history boundary; it never
clears session state, pending commands or the shared history. Only actual traffic cursor gaps show a missed-events warning. Normal buffer
rotation is silent. Follow starts enabled; upward manual scrolling disables it,
downward scrolling back to the bottom reactivates it, and R6 ⏬ Follow jumps directly
to the latest entry. Stable row identities preserve scrollback during rotation.

F8 **Next page** cycles between two bottom pages, with a page indicator. F7
Filters remains fixed on both pages. R7 navigates to Connection settings and back
to Terminal; F5 and F6 are unassigned on ordinary terminal pages. The terminal
workspace has no action toolbar and no explicit
status-query action; shared background polling remains responsible for status.

| Bottom slot | Page 1 | Page 2 |
| --- | --- | --- |
| F1 | ▶️ Guarded `sendRealtime(CYCLE_START)` | Confirm, then `reset()` handshake |
| F2 | ⏸️ `sendRealtime(FEED_HOLD)` | Copy selected log text |
| F3 | ⬆️ Prev Cmd | Clear view |
| F4 | ⬇️ Next Cmd | Unassigned |
| F5 | Unassigned | Unassigned |
| F6 | Unassigned | Unassigned |
| F7 | Filters (funnel icon) | Filters (funnel icon) |
| F8 | Next page (1/2) | Next page (2/2), wrapping to page 1 |

R6 **⏬ Follow** has reactive active state and remains available on both bottom
pages and in Filters. R7 **Connection** remains the terminal navigation key.
Connection settings and modals replace these right-bank bindings.

Upward manual scrolling disables Follow; returning to the bottom enables it.
R6 ⏬ Follow jumps to the newest retained event and follows incoming output; disabled Follow preserves scrollback with stable
session/sequence keys. Switching modes or visiting settings retains the page,
filter menu, list and prompt. History/copy actions expose reactive enabled states.
Realtime and paging actions opt out of keyboard repeat; realtime requests
reserve separate in-flight slots. Reset confirmation warns about interrupting
work and invalidating
pending commands. Core enforces manual command admission separately from Cycle
Start. F1 is available for a fresh synchronized `Idle` or completed `Hold:0`,
including after command acknowledgment. It is disabled during Run, Hold:1,
Jog, Door, alarm, reset and stale/unknown/disconnected status. Explicit activation
sends one realtime `~`; it never automatically resumes or resubmits a command. The `>` prefix and editable line share font metrics and vertical
alignment inside the field decoration; the entire 48 dp prompt is a focus target.

### Connection form

Connection settings retain a complete serial/TCP draft separate from the active
endpoint. Selecting a field enables native text editing, selection and paste in
place, with a touch keypad to its right. Numeric fields use the single-page NUM keypad.
Hostname and device fields stack a two-page QWERTY keypad (lowercase and uppercase)
above NUM+, the same independently paged number and symbol keypad used alongside
CODE in Terminal and MDI. Both
letter pages have a double-width Space key beside N and M. Characters outside the
touch keymaps can be entered with the physical keyboard or pasted.
F7 or keypad Enter applies the field to the draft. F8 cancels the edit. The
form remains visible with the field-edit softkeys until the edit closes.

NUM, NUM+ and QWERTY use the same workspace grid unit as Terminal and MDI. Switching
between numeric and text fields keeps the standard key dimensions. The form and
keypad scroll as needed on constrained viewports.

| Slot | Serial | TCP |
| --- | --- | --- |
| F1 | Toggle to TCP | Toggle to Serial |
| F2 | Select Port 🔗 | Edit Hostname/IP |
| F3 | Select Baud 📈 | Edit Port |
| F4 | Advanced 🎛️ | Unassigned |
| F5 | Post-Connect Actions | Post-Connect Actions |
| F6 | Connect / Disconnect | Connect / Disconnect |
| F7 | Save | Save |
| F8 | Restore saved/default form | Restore saved/default form |

Switching transport keeps unsaved field values. F6 reflects actual lifecycle,
permits cancellation of a pending handshake, and requires interruption confirmation
before disconnecting active/pending sessions. Repeated activations cannot start
competing operations. Disconnect waits for Connect persistence once that phase
has begun; Save and running Disconnect cannot be cancelled by another Disconnect.
Save persists without changing the session; Connect persists after successful
connection. Progress, actual endpoint, failures and stale/unavailable observations
come from the shared owner. Deliberate disconnect is neutral. Core's existing
500 ms polling remains independent of the screen.

F8 confirms replacing dirty edits with the latest saved complete form, including
the selected transport, both transport fields and post-connect choices. Without
a valid saved record it restores blank endpoint fields and normal defaults.
It never sends a controller reset. R7 Terminal returns to the retained console.

Port and baud choices open `OptionSelectDialog<R>`, a typed screen with a
centered, scrollable `BaseDialog` surface. Up to six numbered touch rows map
to F1–F6. More than six choices add F7 **Next page**, which wraps after the
last page. F8 cancels and preserves the parent draft. Both key banks are
replaced while any selector or editor is open.

Port selection starts discovery each time it opens. While discovery is pending,
the dialog shows a loading message and allows cancellation. Empty or failed
discovery points to manual entry. Devices are advisory and may disappear before
connection. The baud selector offers common rates including 115200, and the
baud field also permits custom entry.

Advanced serial settings own a separate draft. F1 cycles data bits 5–8, F2 stop
bits, F3 parity, F4 flow control, F5 DTR and F6 RTS. F7 Save applies to the parent
draft with green positive emphasis; it does not persist. F8 cancels unchanged
values immediately or asks before discarding changed values. Normal defaults
are 115200, 8N1, no flow control, DTR on and RTS off.

The shared Post-Connect Actions screen uses F1 reset Off/On, F2 firmware info
Off → $I → $I+ → Off, F3 parameters $#, F4 parser state $G, F7 Save & return
and F8 Cancel. Defaults are reset Off, firmware $I, parameters/parser On.
Saving returns choices to the parent draft; cancellation discards local edits.
Reset consequences are visible and every manual connection with reset selected
requires a separate Ctrl+X confirmation. Startup cannot supply that consent and
always skips reset. Passive queries run after handshake/reset through the shared
session. Connection diagnostics retain their outcomes; responses remain in Terminal.
Query timeout does not imply disconnected health; uncertain correlation skips
remaining queries. There is no automatic replay.

ControllerSettings owns loading, validation, savedDraft and persistence. The UI
does not instantiate Preferences, sessions or transports. v1 records retain
explicit DTR/RTS; v2 adds post-connect preferences and complete saved form fields.
ScreenStack and ModalDialog provide typed results and covered-screen isolation.
Application shutdown closes and awaits the shared connection without sending
automatic machine-control commands.


### CNC/G-code touch keyboard

`ui.components.controls.GCodeKeyboard` stacks the letter `KeyCluster` above the
numeric cluster in a right-hand workspace panel beside the Util Terminal console
or the local MDI buffer. The console/input occupies the full available height.
Each cluster has independent retained
`KeyClusterState`, a square grid and one page-count/Next control below it, wrapping
to the first page. Single-page maps omit the page control. Paging preserves focus,
caret, selection and the other cluster's page. Empty positions retain their space.
A key can span adjacent columns in the same row. Its width includes the gaps
between those columns, and its height stays the same as other keys.
The control does not use F8/R7 or the screen-navigation mechanism.

Default letter pages (each cell lists columns 1–4):

| Row | Page 1 | Page 2 |
| --- | --- | --- |
| 1 | `O N G P` | `B C D E` |
| 2 | `X Y Z A` | `H L Q U` |
| 3 | `I J K R` | `V W` then two empty slots |
| 4 | `F S T M` | Four empty slots |

Default numeric/operator pages:

| Row | Page 1 | Page 2 |
| --- | --- | --- |
| 1 | `7 8 9 ⌫` | `# / = ⌫` |
| 2 | `4 5 6 *` | `( ) $ *` |
| 3 | `1 2 3 +` | `[ ] % +` |
| 4 | `− 0 . ↵` | `− Space : ↵` |

Minus inserts ASCII `-`; Space inserts one space. Backspace and Enter stay at
slots 3 and 15 on both numeric pages. Backspace has a red background and Enter
has a green background, both with white symbols. Colors follow the typed action
rather than the label or position, including custom keymaps. `*` and `+` stay at
slots 7 and 11.

`AppShell` provides `ui.layout.LocalGridUnit` for proportional Compose layouts.
It chooses the largest whole-pixel grid unit within **44–72 dp** that satisfies
fixed reference dimensions within **65%** of the main workspace width and
**75%** of its height. The workspace excludes the header, both softkey banks
and native window decorations. The unit falls back to the 44 dp minimum when
the reference does not fit. Resizing the window updates the unit. Switching
screens, fields or pages keeps it stable.

The fixed reference dimensions are calibrated to QWERTY stacked above NUM+:

- **Width** is 12.7 units before rounding. QWERTY has ten columns and nine
  gaps of 0.3 units. Its spanning Space key occupies two columns and the
  intervening gap within that footprint. NUM+ has four columns, two 0.3-unit
  gaps and one 0.6-unit gap, for a width of 5.2 units.
- **Height** is 8.5 units before rounding. The two clusters have seven rows
  in total and five internal gaps of 0.3 units. The fixed vertical allowance
  totals 120 dp before pixel rounding. It covers two 44 dp page controls, two
  8 dp gaps above them and one 16 dp gap between the clusters.

The calculation rounds each gap and each fixed dimension separately to match
Compose measurement. Page control heights round up to meet their 44 dp minimum.
`ui.layout` owns the fixed dimensions. Keypad controls consume the resulting
unit, and changing a keymap alone leaves the shared grid unit stable.

A standard keypad key is **1 × 1 grid unit**, excluding gaps. Compose layouts
can use `LocalGridUnit.current * 0.5f` for a margin or
`LocalGridUnit.current * 0.3f` for spacing. `GCodeKeyboard`, `KeypadPanel` and
`KeyCluster` default to the shared unit. Passing `keySize` explicitly sizes
that instance. The budget only determines the unit. Each keypad panel uses
the actual space its screen provides.

Configured ordinary row and column gaps default to 30% of the rendered key
size, with a total 60% gap before the numeric action column. A spanning key
includes the rounded gaps between its columns.

The keypad panel aims for **30 dp** around the group and reduces the margin
symmetrically on a constrained axis. Its page controls have a 44 dp minimum
height, with up to 8 dp of space above each and up to 16 dp between clusters,
depending on available height. The clusters share a right edge, and the group
aligns to the bottom right of the panel when it fits. The panel scrolls on
each axis where the grids and page controls exceed the available space at the
shared size.

At the minimum supported **1024×720 viewport**, Terminal and MDI have 532 dp
of height for the panel. At 1× scale, their two 44 dp grids and page controls
need 518 dp, leaving 7 dp for each vertical margin. Terminal and MDI allow up
to 40% of workspace width, capped at 400 dp, for the keys and page controls,
with the margin outside that budget.

`KeypadLayout` is a copied configuration snapshot with rows/columns, a list of
row-major slot maps, labels, typed actions, ordinary spacing and optional total
gaps before columns. `Keymaps` supplies defaults. For customization, construct
screen-owned states and pass them to the same renderer; no renderer changes are
needed. For example:

```kotlin
val customLetters = KeyClusterState(KeypadLayout(
    name = "Macros", rows = 2, columns = 3,
    pages = listOf(mapOf(0 to KeypadKey("G65", KeypadAction.Insert("G65 ")))),
    spacing = .30f, gapsBeforeColumns = mapOf(2 to .60f),
))
// From a retained screen composition:
GCodeKeyboard(onAction = ::handleInput, letters = customLetters)
```

`KeyClusterState` belongs to one cluster/owner. Keep supplied instances stable;
construct a replacement to install another layout. Default states are remembered
inside the retained composition. Maps and page choices are in memory only; there
is no visual keymap editor or persistent UI-preferences schema. Controller endpoint
settings and their persistence do not own keyboard preferences.

The renderer reports `Insert`, `Backspace`, or `Enter` intentions through a required
runtime callback. Every owner explicitly binds Enter to its input's semantics;
there is no synthesized OS key event or global input dispatcher. Keys/page controls
cannot acquire text-input focus. Each key/page retains its interaction source.
A click remains valid when the pointer moves slightly inside the pressed key;
leaving the key, cancelling the gesture or covering the screen cancels it.
First input is supported without a prior hover, including after command
acknowledgment or movement completion. `editWithKeypad` is an optional input-side helper
for insertion/selection replacement and Backspace at the caret, including a
supplementary Unicode code point; an explicit edit finalizes IME composition.
Enter always invokes the supplied binding rather than inserting text implicitly.
Physical keyboard, mouse selection, paste and touch remain Compose input behavior.
Terminal binds Enter to the same guarded `submit()` as physical/IME Enter and
keeps all validation and shared core admission. MDI binds Enter to local newline
insertion; it cannot execute the buffer. No keyboard control communicates with a
session/transport or triggers realtime/motion controls.
