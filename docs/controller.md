# Controller execution buffer

The internal `core.controller.CommandBuffer` binds one FIFO and one lazy dispatch
worker to a machine session and its command epoch. Its default queue holds eight
commands plus one held by the worker. One source producer can also be suspended
while enqueueing its next line. The desktop uses the separate
[controller connection](controller-connection.md).

The operation supplies its execution scope and starts the buffer once. The buffer creates and manages its private worker as a child of that
execution. Cancelling the execution retires the buffer even while it waits for
an acknowledgment. Calling `suspend()` gates new sends and retains queued and
worker-held lines.
The worker claims each send by atomically observing the open gate. A claimed
send can settle while suspended. The read-only `suspended` property reports
a temporary hold. Calling `resume()` releases that hold. Closure permanently
prevents further claims. The worker executes the
line through the session, reports transmission and reply events synchronously
with their source position, and stops on rejection or failure. A successful
write can still have an unknown reply. A failed write can have an uncertain
transmission outcome.

Finishing production drains the FIFO. Closing cancels the worker, discards
queued commands and wakes suspended enqueue calls. Awaiting closure observes
worker termination, including settlement of the in-flight session call.
The session remains owned by the caller. Physical completion and realtime
machine control are separate from this local lifecycle.

## Session and execution ownership

The public `Controller` owns one machine session and publishes its snapshots.
It polls for status every 500 ms after connection, using the session's realtime
writer. Polling skips reset and ends with the session. The session reader
handles report parsing.

`run(operation)` atomically reserves the supplied [operation](machine-operations.md#source-owned-controller-operations)
against the current protocol epoch and a synchronized Idle report at most three
seconds old. Controller supplies the parent scope independently of the caller.
Its active-operation flow retains the handle until the operation has finished
and its dispatch worker has terminated.

Controller sends realtime commands and coordinates the active operation's
dispatch. Feed Hold pauses dispatch before the realtime write. An explicit Cycle
Start from fresh Hold:0 authorizes the operation to resume once it observes a
later fresh Run or Idle for the same hold. Reset, replacement, disconnect and
shutdown interrupt the operation and await its cleanup. A session failure or
invalidated command epoch also interrupts the affected operation.
