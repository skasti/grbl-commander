# grblHAL controller storage

## Firmware protocol

Controller storage depends on the driver and optional plugins. The grblHAL
filesystem stream plugin exposes the following commands. Its VFS can include
FatFs (typically SD mounted at `/`) and LittleFS (often `/littlefs`).

| Command | Observed protocol |
| --- | --- |
| `$FI` | Reports `[FS:<driver>@<mount path>]`, with optional ` size <bytes>, free <bytes>` before the closing bracket. A mounted root does not establish that a particular operation is available. |
| `$PWD`, `$CWD=<path>` | Reports `[CWD:<path>]` or sets the shared working directory. The plugin returns error 63 when changing directory fails. |
| `$F+` | With no argument, lists visible files of all types. `[FILE:<path>\|SIZE:<bytes>]` may end in `\|UNUSABLE]` for a filename the plugin rejects. Hierarchical mode (`$650` bit 2) lists immediate children and uses `SIZE:-1` for directories. Recursive mode lists paths with a depth limit and omits empty directories. The file report has no modification time. |
| `$FM`, `$FU` | SD mount and unmount commands perform explicit media operations. Filesystem discovery uses the existing mounts. |
| `$F=<path>`, `$F+=<path>` | Arguments can start controller-side execution. The browsing sequence uses only the argument-free form of `$F+` and uses `$CWD` for navigation. |
| `$F<=<filename>` | Dumps file data onto the normal output stream with normalized line endings and a trailing line terminator. There is no payload framing to distinguish a file line containing `ok`, `error:n` or a status-looking string from live protocol messages. |
| `$YUP=<filename>` | Optional YModem download from the controller, despite the command name. It redirects the stream input to the transfer protocol and waits for the receiver. Supporting it requires coordinated binary framing, realtime behavior and recovery on the existing session. |

In the pinned plugin, `$F+` also clears the pending file rewind selection.
The implementation must admit browsing only when the shared session and
machine state allow it. It must avoid changing `$650` to implement browsing.

Sources: [filesystem stream plugin](https://github.com/grblHAL/Plugin_SD_card/blob/4bed4683e70333d63c47d03a4068b9d202e93c66/fs_stream.c),
[YModem implementation](https://github.com/grblHAL/Plugin_SD_card/blob/4bed4683e70333d63c47d03a4068b9d202e93c66/ymodem.c),
[VFS implementation](https://github.com/grblHAL/core/blob/a53a16a4d756f12a27006152ba3a99b5ec46b2c7/vfs.c).
