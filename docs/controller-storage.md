# grblHAL controller storage

## Application API

The application-owned `ControllerConnection` exposes `discoverStorage()`
and `listStorage(absolutePath)`. Discovery queries mounted VFS roots. Listing
returns immediate children with absolute paths, file sizes when reported, and
the firmware's unusable-name marker. Recursive listings infer directories
from descendant paths and may omit empty directories. The result exposes
this limitation. Older firmware that cannot report its working directory or
perform safe `$CWD` navigation reports unsupported for the affected operation.

Each request reserves the active session's normal dispatch. It requires fresh,
synchronized Idle status without an active or queued SD job. Terminal lines,
machine operations and Cycle Start cannot interleave with a browse request.
Status polling and Feed Hold remain available. An explicit reset cancels
storage work before sending reset. Disconnect and reconnect dispose of the
old session and its storage task together.

Listing reads the working directory from the controller, changes it only
when needed, and attempts to restore it before releasing dispatch. A reset,
disconnect, or loss of response correlation can prevent restoration. A later
request obtains the working directory again. The application cannot reserve
the working directory against independent clients. Use a single controller
client when browsing.

Unsupported commands, disconnected state and controller rejection are
separate from a successful empty listing. Known media, directory and
permission errors retain their numeric firmware codes. Listings use the
[session's bounded response collector](grbl-session.md#alarms-and-command-correlation).
The collector accepts up to 256 information lines per request, including
unsolicited messages. An oversized response fails without returning a partial list.

`controllerStorageReadSupported` is false. The text dump has no payload
framing, and the optional binary transfer temporarily owns the controller's
input, including bytes used for realtime control. The line session cannot
safely offer either transfer. A future reader needs lossless streaming and
verified normal/realtime recovery, including for files of 100,000 lines or
more. The storage contracts are specific to the controller backend. Native
filesystem integration can validate common path and metadata needs.

## Verification

Protocol fixtures are based on the pinned upstream source. The in-memory
session tests exercise mount reports, nested and recursive listings, unsupported
commands, missing media, cancellation, reset, reconnect and dispatch exclusion.
Filesystem plugin behavior over physical serial and TCP remains unverified.

## Firmware protocol

Controller storage depends on the driver and optional plugins. The grblHAL
filesystem stream plugin exposes the following commands. Its VFS can include
FatFs (typically SD mounted at `/`) and LittleFS (often `/littlefs`).

| Command | Observed protocol |
| --- | --- |
| `$FI` | Reports `[FS:<driver>@<mount path>]`, with optional ` size <quantity>, free <quantity>` before the closing bracket. Capacity and free space can be rounded, for example `7.45 GB`. A mounted root does not establish that a particular operation is available. |
| `$PWD`, `$CWD=<path>` | Reports `[CWD:<path>]` or sets the shared working directory. The plugin returns error 63 when changing directory fails. |
| `$F+` | With no argument, lists visible files of all types. `[FILE:<path>\|SIZE:<bytes>]` may end in `\|UNUSABLE]` for a filename the plugin rejects. Hierarchical mode (`$650` bit 2) lists immediate children and uses `SIZE:-1` for directories. Recursive mode lists paths with a depth limit and omits empty directories. The file report has no modification time. |
| `$FM`, `$FU` | SD mount and unmount commands perform explicit media operations. Filesystem discovery uses the existing mounts. |
| `$F=<path>`, `$F+=<path>` | Arguments can start controller-side execution. The browsing sequence uses only the argument-free form of `$F+` and uses `$CWD` for navigation. |
| `$F<=<filename>` | Dumps file data onto the normal output stream with normalized line endings and a trailing line terminator. There is no payload framing to distinguish a file line containing `ok`, `error:n` or a status-looking string from live protocol messages. |
| `$YUP=<filename>` | Optional YModem download from the controller, despite the command name. It redirects the stream input to the transfer protocol and waits for the receiver. Supporting it requires coordinated binary framing, realtime behavior and recovery on the existing session. |

In the pinned plugin, `$F+` also clears the pending file rewind selection.
The application checks Idle and the realtime SD field before listing. It reads
`$650` without changing it.

Source revisions include the [filesystem stream plugin](https://github.com/grblHAL/Plugin_SD_card/blob/4bed4683e70333d63c47d03a4068b9d202e93c66/fs_stream.c),
[YModem implementation](https://github.com/grblHAL/Plugin_SD_card/blob/4bed4683e70333d63c47d03a4068b9d202e93c66/ymodem.c),
[VFS implementation](https://github.com/grblHAL/core/blob/a53a16a4d756f12a27006152ba3a99b5ec46b2c7/vfs.c).
