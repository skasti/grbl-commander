# Util Terminal and traffic diagnostics

Terminal is the [Util root](controller-connection.md#util-navigation).
The console observes traffic and submits explicit operator actions through
the shared connection.

## Console and prompt

The workspace has compact endpoint/controller status, a dark virtualized monospaced
output region and an editable `>` prompt inside the same console surface. Output
entries distinguish TX, RX, category,
timestamps and failed/uncertain handoffs. Original unfamiliar text is retained.
Control characters are escaped. Selection and copying are supported within one
output entry. Selection spanning multiple lazy entries is not implemented.

Incoming events never replace the prompt value, caret or focus. Ordinary
text editing, selection and paste use Compose text input. Enter or the onscreen
keyboard Done/Enter action is the only submission action. Held Enter and concurrent
IME/key submission cannot duplicate a pending request. The prompt applies the
[normal-line validation rules](grbl-session.md#protocol-framing) and the
[core admission checks](controller-connection.md#manual-command-admission).
Local admission or failure feedback appears beneath the prompt. A failed
submission preserves the draft. Success clears the
unchanged line after acknowledgment, without claiming motion completion. Up/Down
and F3/F4 on the first bottom-softkey page browse up to 100 recent transport handoffs
and restore the unsent draft. See [command recall ingestion](#terminal-subscription-and-recall).

## Traffic filters and follow

F7 **Filters**, with a funnel icon, replaces the current bottom bank with a
contextual filter menu. F7 **↩ Back** restores the previously selected bottom
page. Paging is unavailable while filtering. Util navigation remains available.
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
The display filter applies on receipt and leaves session processing and shared
alarm and connection indicators untouched. Follow starts enabled. Upward
manual scrolling disables it, and returning to the bottom reactivates it.
R6 ⏬ Follow jumps directly to the latest entry.

## Realtime controls and softkey pages

F8 **Next page** cycles between two bottom pages, with a page indicator. F7
The workspace has no action toolbar or explicit status-query action. It uses
[shared background polling](controller-connection.md#freshness-and-background-polling).

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
pages and in Filters. See [Util navigation](controller-connection.md#util-navigation)
and [modal bindings](dialogs.md#basedialog-and-modaldialog) for covered screens.

Switching modes or visiting settings retains the page, filter menu, list and
prompt. History and copy actions expose reactive enabled states. Realtime and
paging actions suppress keyboard repeat. Realtime requests reserve separate
in-flight slots. Reset confirmation warns about interrupting work and
invalidating pending commands. Cycle Start uses the shared
[core admission rule](controller-connection.md#manual-command-admission).

The `>` prefix and editable line share font metrics and vertical alignment.
The entire 48 dp prompt is a focus target.

## Terminal subscription and recall

Terminal consumes the [shared traffic stream and diagnostic snapshot](controller-connection.md#traffic-aggregation-and-retention).
It uses the [session event categories and TX outcomes](grbl-session.md#communication-events)
to render rows and update command recall.

```mermaid
flowchart TD
    live["Live core traffic"] --> merge["Merge by cursor"]
    history["Core diagnostic snapshot"] --> merge
    merge -- "Cursor gap" --> warning["Missed-events warning"]
    merge --> recall["Command recall"]
    merge --> filters["Display filters"]
    filters --> rows["Visible scrollback ring"]
```

Terminal continuously subscribes while its retained screen is composed, including
inactive modes and covered Connection settings. Subscription is established before
seeding from the diagnostic snapshot. Cursor overlap is ignored. Bounded overflow
during handoff remains detectable. The snapshot's oldest retained cursor establishes
the initial baseline, without warning about earlier normal diagnostic rotation.
Terminal owns a 10,000-entry Compose-observable indexed ring. Only events admitted
by current filters consume capacity. All events advance its loss cursor and update
command recall. Filter changes do not delete or reconstruct existing entries.
Slots and ring indices update atomically on the desktop UI dispatcher. Ingestion
yields after bounded work, and LazyColumn renders retained entries with stable
session/sequence keys. Only cursor gaps show a missed-events warning. Normal rotation is silent and
stable row identities preserve scrollback. Clear view advances a local history
boundary and clears only displayed rows. Session state, pending commands and
core history are unaffected. Persistent/file logging is not implemented.

In-memory command recall records ordinary command TX handoffs before acknowledgment.
Local transport rejection removes its exact session/attempt entry. Controller error,
timeout and uncertain handoff retain it. The command operation still awaits its
acknowledgment. No synthetic TX echo, automatic retry or replay is introduced.
