# grblHAL alarm protocol

The protocol reference is grblHAL Core
[`23bf87570365a8daa33a735a98d42808a3266dbb`](https://github.com/grblHAL/core/tree/23bf87570365a8daa33a735a98d42808a3266dbb),
also recorded by the pinned official Simulator. Alarm categories are protocol
metadata, not machine-operation preconditions or evidence of position/homing.

| Codes | Category | Recovery considerations for Application/operator |
|---|---|---|
| 1, 10, 17, 20 | Critical stop: limit, E-stop, motor, I/O expander | Address the cause and explicitly reset before ordinary operation |
| 2 | Critical soft limit | Core describes position as retained; blocking protocol handling still requires reset |
| 3 | Interrupted cycle | Position may be lost; rehoming is recommended |
| 4, 5 | Probing failure | Investigate; never automatically repeat probing/motion |
| 6–9, 15, 18 | Homing failure | Investigate before another homing cycle; do not assume homed state |
| 11 | Homing required | `$H` is the normal operator-requested recovery, subject to controller checks |
| 12, 13 | Limit/probe protection | Clear the underlying input/cause before proceeding |
| 14, 19 | Spindle/Modbus failure | Diagnose equipment and communication before resuming |
| 16, 21, 22 | Self-test/NVS/buffer failure | Dedicated diagnosis; ordinary unlocking is not assumed sufficient |
| Others | Unknown/extension | Preserve the numeric code and any controller-provided description; conservative handling |

`ControllerAlarm` classifies known codes and supplies concise fallback guidance.
Only 1, 2, 10, 17 and 20 are critical under `alarm_is_critical()`; unknown codes
have unknown criticality, rather than being labelled critical. `ControllerStatus`
extracts optional codes from `Alarm:n` without interpreting Hold/Run substates as
alarms. `GrblProtocol` recognizes optional `[ALARMCODE:n||description]` metadata.
The session exposes `alarmCode`, the computed `alarm` descriptor and optional
controller-provided `alarmDescription`. Alarm presentation is independent of
`connection`, `normalCommandsSynchronized`, `resetRequired` and `resetInProgress`.
The session-specific recovery contract is described in [GRBL session](grbl-session.md#alarms-and-command-correlation).

## Source findings

- [alarms.h](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/alarms.h): numeric codes and the exact criticality predicate.
- [alarms.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/alarms.c): descriptions and plugin alarm registration.
- [protocol.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/protocol.c), `protocol_exec_rt_system()`: critical events clear the line and receive buffer, wait for reset and blocking inputs, and continue status reporting. `protocol_poll_cmd()` can process diagnostic/system lines during the blocking loop; an acknowledgment from such a line does not prove normal processing has resumed.
- `protocol_main_loop()` checks real-time events before executing a completed line and can exit on abort before reporting `ok`/`error`. A critical event can therefore discard a line without acknowledgment. Non-critical alarms can be followed by an acknowledgment, but no general acknowledgment guarantee exists for every abort/plugin path.
- [system.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/system.c), `disable_lock()`, `check_status()` and `go_home()`: unlock and homing enforce controller checks. In particular homing-required state is not a reason to automatically send `$X`.
- [report.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/report.c): status can include `Alarm:n` when alarm substatus/all-fields reporting is enabled; `$EA` emits alarm descriptions.
- [state_machine.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/state_machine.c): alarm codes are retained in Alarm/E-stop and cleared when leaving those states.
- [grbllib.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/grbllib.c): reset clears the receive buffer and parser before emitting the initialization banner.
- [System command reference](https://github.com/grblHAL/grblhal_docs/blob/main/content/markdown/04-Reference/01-system-commands-reference.md): reset, `$H`, `$X`, status and `$EA` commands.

Ordinary acknowledgments have no request IDs. A status report, delay, empty line
or diagnostic command does not provide a generic proof that an older response
cannot still arrive. A known outstanding non-critical response can be consumed
without assigning it to another command; if it never arrives, reset is the
explicit synchronization boundary. Critical alarms must not rely on receiving
that response at all. Reset does not establish a safe position, successful
homing, cleared inputs or completed operation. No protocol action is a substitute
for physical-machine verification.

## Explicit reset boundary

`reset()` sends 0x18 exactly once on the same transport. Pending/queued normal
work is invalidated, not replayed. Normal admission stays closed until an
initialization banner and a fresh subsequent report establish the new parser
boundary. Reset waits under a deadline; E-stop/motor-fault inputs may prevent it
from taking effect. Failure/cancellation preserves a usable connection for
status and another operator-requested reset; it never clears uncertainty by
waiting an arbitrary duration or by sending a new diagnostic line.

Core can retain a critical alarm code after reset while leaving the blocking
loop. `report.c` emits `$C:1` in its all-fields `FW:grblHAL` report while
`sys.blocking_event` is active. This flag is distinct from the alarm code, and
absence in an ordinary partial report does not prove it is false. A retained
known pre-reset code after a witnessed reset does not itself require repeated
resets. This retained-code history survives a bare post-banner Alarm report, but
ends on a non-Alarm report, a changed alarm code or an explicit `ALARM:n` event.
Alarm transitions after that point follow the ordinary correlation and reset
rules, including recurrence of the same code. An unseen code is not classified
as retained merely because an all-fields report has no `$C:1`. A reported active
blocking flag requires reset even if the alarm code is unchanged.
Unlock or homing remains a separate, explicit Application/operator action subject
to `system.c` checks. Reset during motion can also produce an interrupted-cycle/
homing alarm and lose position.

## Verification coverage

Hardware-free tests cover exact criticality for codes 1–22, optional/unknown
codes and descriptions, critical alarms without acknowledgments, non-critical
late `ok`/`error`, alarm after an earlier acknowledgment, empty queues,
homing-required with explicitly requested `$H`, status-only alarms, repeated
status during recovery, post-reset alarm transitions after Idle and code changes,
partial/all-fields/blocking reports, explicit alarm notifications in either order,
preserved real-time access, reset timeout/failure, pre-banner stale replies and
post-reset synchronization without replay.

The official Linux Simulator test enables hard limits only in its isolated NVS,
starts a non-motion dwell and toggles the simulator's documented X-limit input
through stdin. It verifies the discarded dwell response, continued critical
status reporting, released input, explicit reset on the same production TCP
session, retained Alarm:1 with no blocking flag, and subsequent explicit `$X`
and identification. The simulator's default driver does not expose an E-stop
interrupt through its control mask; E-stop, motor, expander, soft-limit, homing,
probing and extension alarm variants are tested with in-memory protocol messages,
not physical hardware. No probing or homing is automatically started by these
integration tests. Simulator timing is approximate and cannot establish
physical-machine safety.
