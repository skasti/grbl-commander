# Controller execution buffer

The internal `core.controller.CommandBuffer` binds one FIFO and one lazy dispatch
worker to a machine session and its command epoch. Its default queue holds eight
commands plus one held by the worker. One source producer can also be suspended
while enqueueing its next line. The desktop uses the separate
[controller connection](controller-connection.md).

The execution owner supplies a structured parent scope and starts the buffer
once. The buffer creates and manages its private worker as a child of that
execution. Cancelling the execution retires the buffer even while it waits for
an acknowledgment. Calling `suspend()` gates new sends and retains queued and worker-held lines.
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

The public `Controller` connects one machine session and publishes its snapshots.
`run(operation)` atomically reserves the supplied [operation](machine-operations.md#source-owned-controller-operations)
against the current protocol epoch and a synchronized Idle report at most three
seconds old. The controller parents the execution independently of the caller.
Its active-operation flow retains the handle until the producer and dispatch
worker have terminated.

Feed Hold gates the active buffer before the realtime write. Cycle Start from
fresh Hold:0 permits further dispatch once a subsequent fresh Run or Idle
report arrives. Session observation continues while the producer waits for
capacity and while the operation waits for physical completion. Reset,
replacement, disconnect and shutdown serialize session changes and await old
execution cleanup. A session failure or invalidated command epoch interrupts
the affected operation.
