# VM lifecycle execution model

This document records the software-side lifecycle guarantees implemented by PhoenixVM. It does not claim physical guest acceptance on a phone.

## Contract

The source specification (section 6) defines the states `OFF`, `STARTING`, `RUNNING`, `PAUSED`, `SUSPENDED`, `STOPPING`, `ERROR` and `RECOVERY`, together with start, clean stop, force stop, pause, resume and reboot operations.

PhoenixVM applies the following runtime rules:

1. Commands for the same VM are accepted in FIFO order. Commands for different VM IDs may execute concurrently.
2. A duplicate start is idempotent while that VM already has a live backend process.
3. Pause is accepted only from `RUNNING`; resume is accepted only from `PAUSED`; reboot is accepted only while a live process is in `RUNNING` or `PAUSED`.
4. A stop received during startup waits behind the startup command. It therefore observes either a completed backend handle or a recorded startup failure; it cannot race ahead of process registration.
5. Process waiters never mutate VM state directly. They enqueue an exit transition into the same per-VM command queue.
6. Exit cleanup removes a process with the key-and-handle identity pair. A delayed exit notification for an older process cannot remove a newer process registered for the same VM ID.
7. Clean/force stop follows bounded escalation: accepted graceful request and wait, process destroy and wait, then destroy-forcibly and final wait. A VM is stored as `OFF` only when process exit is confirmed. Failure to confirm termination remains visible as `ERROR`.
8. If the store says a VM is active but no runtime process handle exists, a stop request moves it to `RECOVERY` and writes a recovery plan instead of falsely reporting `OFF`.

## Concurrency and resource isolation

A shared worker pool backs one serial queue per VM. This avoids a global lock around long QMP/crosvm operations: VM-A can progress independently of VM-B, while each VM's own lifecycle transitions remain ordered. Queue failures are reported and do not strand later commands for that VM.

Process-monitoring and process-wait threads remain separate from the lifecycle queues. Monitoring is read/observe-only; all terminal state changes occur through the queue.

## Automated coverage

JVM tests assert:

- FIFO execution for commands with one VM ID;
- concurrent progress for distinct VM IDs;
- queue progression after a command fails;
- graceful exit without unnecessary escalation;
- escalation to forced termination when a backend does not exit;
- failure remains failure when the process is still alive after the final termination attempt.

## Acceptance boundary

These tests validate deterministic software-side orchestration. They do not replace Android-device acceptance for guest shutdown behavior, service recreation, process death under memory pressure, thermal stress, or repeated VM start/stop cycles.
