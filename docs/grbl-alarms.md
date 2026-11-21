# grblHAL alarm protocol

The protocol reference is grblHAL Core
[`23bf87570365a8daa33a735a98d42808a3266dbb`](https://github.com/grblHAL/core/tree/23bf87570365a8daa33a735a98d42808a3266dbb),
also recorded by the pinned official Simulator. Alarm categories are protocol
metadata, not machine-operation preconditions or evidence of position/homing.

## Alarm categories

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

`ControllerAlarm` supplies fallback descriptions and uses the firmware's
exact criticality predicate. Unknown codes have unknown criticality. See
[session alarm handling](grbl-session.md#alarms-and-command-correlation)
for the exposed state and command correlation rules.

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

## Explicit reset boundary

The firmware clears its receive buffer and parser before emitting the
initialization banner. Its all-fields `FW:grblHAL` report includes `$C:1`
while `sys.blocking_event` is active. Absence in a partial report leaves
blocking status unknown. Firmware can retain an alarm code after leaving
the blocking loop.

Blocking inputs, including E-stop or motor-fault signals, can prevent reset
from taking effect. Reset during motion can produce an interrupted-cycle or
homing alarm and lose position. Reset alone establishes neither safe position
nor successful homing, cleared inputs or completed motion. Unlock and homing
are separate operator requests subject to firmware checks.

See the [session reset contract](grbl-session.md#reset-and-recovery) for the
application handshake, retained-code handling and normal command admission.

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
