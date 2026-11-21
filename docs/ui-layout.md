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

## Util connection settings

Util is selected by an icon-only ⚙ header button with accessible name "Util" and
selected semantics/color. Connection health stays in the separate display-only
header indicator, with the current endpoint in the header context. No settings
screen is pushed onto Program, Manual or MDI stacks.

`CommanderApp` retains one Util stack rooted at `mode.utils.terminal.Terminal`
per window. Terminal is visible on first entry. Its R7 **🔗 Connection** opens
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

## Terminal console

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

## Connection form

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
come from the shared owner. Deliberate disconnect clears the displayed connection
error and leaves a neutral observation that machine state is no longer updating.
Unexpected failures remain visible. Background status polling is independent of
the selected screen.

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
