# Controller execution buffer

The internal `core.controller.CommandBuffer` binds one FIFO and one lazy dispatch
worker to a machine session and its command epoch. Its default queue holds eight
commands plus one held by the worker. One source producer can also be suspended
while enqueueing its next line. The desktop uses the separate
[controller connection](controller-connection.md).

The execution owner supplies a structured parent scope and starts the buffer
once. The buffer creates and manages its private worker as a child of that
execution. Cancelling the execution retires the buffer even while it waits for
an acknowledgment. The owner supplies dispatch permission for each
dequeued line. Granting permission admits that send. The worker executes the
line through the session, reports transmission and reply events synchronously
with their source position, and stops on rejection or failure. A successful
write can still have an unknown reply. A failed write can have an uncertain
transmission outcome.

Finishing production drains the FIFO. Closing cancels the worker, discards
queued commands and wakes suspended enqueue calls. Awaiting closure observes
worker termination, including settlement of the in-flight session call.
The session remains owned by the caller. Physical completion and realtime
machine control are separate from this local lifecycle.
