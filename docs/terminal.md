# Util Terminal and traffic diagnostics

Terminal is the root of Util. R7 **Connection** opens the
[connection form](controller-connection.md#connection-form) on the same retained stack.
The console observes traffic and submits explicit operator actions through
the shared connection.

## Console and prompt

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
controller errors/timeouts/uncertainty and removes exact local transport rejections. Command recall is kept in memory.

## Traffic filters and follow

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

## Realtime controls and softkey pages

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

Switching modes or visiting settings retains the page, filter menu, list and
prompt. History and copy actions expose reactive enabled states. Realtime and
paging actions suppress keyboard repeat. Realtime requests reserve separate
in-flight slots. Reset confirmation warns about interrupting work and
invalidating pending commands. Cycle Start uses the shared
[core admission rule](controller-connection.md#manual-command-admission).

The `>` prefix and editable line share font metrics and vertical alignment.
The entire 48 dp prompt is a focus target.

## Traffic event contract

`MachineSession.communication` is a read-only `Flow<CommunicationEvent>`
observed at the existing coordinated session I/O boundary. No observer reads the
transport or owns acknowledgments. The reader retains each original nonempty
decoded line alongside the parsed category: status, acknowledgment, error, alarm,
startup or information (including unknown extensions). Normal TX retains its
exact encoded line including LF; realtime/status bytes are rendered as printable
ASCII or `0xNN`. Each event has a session-local sequence and `Instant` timestamp.

TX `ATTEMPTED` means handoff to the transport, never delivery, acknowledgment or
motion completion. A failed handoff emits a related event referencing the attempt:
`LOCAL_REJECTION` guarantees no bytes were sent under the transport contract;
`UNCERTAIN` covers cancellation, timeout and I/O failure, including possible
partial writes. Session validation/admission rejection has no TX event. A write
that returns normally remains an attempt observation; normal `ok`/`error` remains
a separate RX event owned by the existing session correlation logic.

The session retains a bounded replay buffer (256 events by default) backed by
`MutableSharedFlow` with `DROP_OLDEST` and non-suspending `tryEmit` publication.
A stalled subscriber cannot cause the latest event to be rejected for responsive
subscribers. Each subscriber resumes with the recent tail; sequence gaps show
which observations it missed. `recentCommunication` exposes a read-only snapshot
of that bounded tail without subscribing or changing controller state.

`droppedCommunicationEvents` counts each source event evicted before handoff to
at least one active subscriber once, even if several subscribers missed it. It
is updated at publication, including the last overflow before traffic stops;
subscriber resumption is not required. One next-sequence cursor per active
subscription supplies this information because `DROP_OLDEST` always accepts
`tryEmit`. Cursor registration/removal and advancement share the short publication
lock; subscriber code runs outside cursor bookkeeping. A value taken from the
backing flow but evicted before cursor advancement is skipped rather than handed
out after being counted as lost. Collection uses the supported `flow` builder,
not inheritance from the unstable `SharedFlow` interface. No rejected-delivery
queue, per-subscriber event buffer or transport reader is introduced.

The aggregate drop count may include losses seen only by a different subscriber. Replay eviction without subscribers and ordinary
history retention are not subscriber overflow. Observation never changes
controller state, normal admission, status parsing, priorities or alarm handling.

## Core stream and retained history

Core subscribes before connecting, capturing handshake traffic. `traffic` is a
read-only `SharedFlow<ControllerTraffic>` backed by `MutableSharedFlow` with 1,024
extra slots, `DROP_OLDEST` and `tryEmit`. Publication never suspends machine I/O;
successful `tryEmit` does not imply every subscriber received every event.
A separate 5,000-event deque retains all observed traffic regardless of UI filters.
`diagnosticSnapshot()` copies it consistently only on explicit request. Entries
carry endpoint, session identity, source sequence, timestamp and global cursor.
The cursor advances by source sequence distance across sessions, so a consumer's
cursor gaps count the union of upstream and core-stream loss once. Multicast-wide
drop counters describe other subscribers too and must not be added to these gaps.
Normal diagnostic retention is not loss. Disconnect and awaited shutdown collect
the remaining session replay tail without duplicate session/sequence identities.

## Terminal subscription and recall

Terminal continuously subscribes while its retained screen is composed, including
inactive modes and covered Connection settings. Subscription is established before
seeding from the diagnostic snapshot. Cursor overlap is ignored; bounded overflow
during handoff remains detectable. The snapshot's oldest retained cursor establishes
the initial baseline, without warning about earlier normal diagnostic rotation.
Terminal owns a 10,000-entry Compose-observable indexed ring. Only events admitted
by current filters consume capacity; all events advance its loss cursor and update
command recall. Filter changes do not delete or reconstruct existing entries.
Slots and ring indices update atomically on the desktop UI dispatcher; ingestion
yields after bounded work, and LazyColumn renders retained entries with stable
session/sequence keys. Normal rotation is silent. Clear view advances a local
boundary and clears only displayed rows. Persistent/file logging is not implemented.

Command recall observes ordinary command TX handoffs independently of acknowledgment.
Local transport rejection removes its exact session/attempt entry; controller error,
timeout and uncertain handoff retain it. The command operation still awaits its
acknowledgment. No synthetic TX echo, automatic retry or replay is introduced.
