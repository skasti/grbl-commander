# Workspace sizing and softkeys

The [desktop shell](ui-layout.md) provides the workspace and renders both fixed banks. Screens provide the bindings through [navigation](screen-stack.md).

## Workspace grid unit

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
`LocalGridUnit.current * 0.3f` for spacing.

## Screen-owned softkey contract

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
`inheritRightKeys`, both false by default. The `definitions()`, `applyOverrides()` and `applyAllOverrides()` map
extensions compose screen-owned maps. Each bank walks from the top
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
Hardware-panel integration is planned and should use the same slot activation function.

Inherited definitions remain reactive: Compose observes `enabled` and `active`
predicates during tile composition and reads observable screen key maps when
resolving slots. Changing an inherited observable override or navigating
changes the effective definition; changes to predicate results do not require
rebuilding definitions. An inactive mode's stack can be inspected but cannot
receive events through the shell's current-stack activation path.

## Softkey pages

`SoftKeyPages<S>` holds the selected page for one screen-owned bank. Screens expose
its observable `keys` through their existing `bottomKeys` or `rightKeys` getter;
stack resolution, inheritance and guarded activation remain unchanged. Common keys
keep their slots and reactive definitions on every page. The reserved Next page
slot (F8 for the bottom bank, R7 for the right bank) shows the current page count
and wraps from the last page to the first. A single page has no paging button.
Next page ignores keyboard repeat. Each screen and bank retains independent page
state; covered or inactive screens never contribute actions without inheritance.

See [CNC and text touch keypads](keypads.md#sizing-and-spacing) for keypad-specific spacing, panel sizing and overrides.
