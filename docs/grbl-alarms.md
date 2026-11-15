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
The session-specific recovery rules are described in [architecture.md](architecture.md).

## Source findings

- [alarms.h](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/alarms.h): numeric codes and the exact criticality predicate.
- [alarms.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/alarms.c): descriptions and plugin alarm registration.
- [protocol.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/protocol.c), `protocol_exec_rt_system()`: critical events clear the line and receive buffer, wait for reset and blocking inputs, and continue status reporting. `protocol_poll_cmd()` can process diagnostic/system lines during the blocking loop; an acknowledgment from such a line does not prove normal processing has resumed.
- `protocol_main_loop()` checks real-time events before executing a completed line and can exit on abort before reporting `ok`/`error`. A critical event can therefore discard a line without acknowledgment. Non-critical alarms can be followed by an acknowledgment, but no general acknowledgment guarantee exists for every abort/plugin path.
- [system.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/system.c), `disable_lock()`, `check_status()` and `go_home()`: unlock and homing enforce controller checks. In particular homing-required state is not a reason to automatically send `$X`.
- [report.c](https://github.com/grblHAL/core/blob/23bf87570365a8daa33a735a98d42808a3266dbb/report.c): status can include `Alarm:n` when alarm substatus/all-fields reporting is enabled; `$EA` emits alarm descriptions.
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
