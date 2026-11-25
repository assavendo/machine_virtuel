# VM lifecycle execution model

This document records the software-side lifecycle guarantees implemented by PhoenixVM. It does not claim physical guest acceptance on a phone.

## Contract

The source specification (section 6) defines the states `OFF`, `STARTING`, `RUNNING`, `PAUSED`, `SUSPENDED`, `STOPPING`, `ERROR` and `RECOVERY`, together with start, clean stop, force stop, pause, resume and reboot operations.

PhoenixVM applies the following runtime rules:

1. Commands for the same VM are accepted in FIFO order. Commands for different VM IDs may execute concurrently.
2. A duplicate start is idempotent while that VM already has a live backend process. A new start is admitted only from `OFF`; persisted active/error states without a registered process are reconciled to `RECOVERY` instead of launching a possible duplicate guest.
3. Pause is accepted only from `RUNNING`; resume is accepted only from `PAUSED`; reboot is accepted only while a live process is in `RUNNING` or `PAUSED`.
4. A stop received during startup waits behind the startup command. It therefore observes either a completed backend handle or a recorded startup failure; it cannot race ahead of process registration.
5. Process waiters never mutate VM state directly. They enqueue an exit transition into the same per-VM command queue.
6. Exit cleanup removes a process with the key-and-handle identity pair. A delayed exit notification for an older process cannot remove a newer process registered for the same VM ID.
7. Clean/force stop follows bounded escalation: accepted graceful request and wait, process destroy and wait, then destroy-forcibly and final wait. A VM is stored as `OFF` only when process exit is confirmed. A process that exits before the stop command is handled as an unexpected exit; a stop that requires destroy/force escalation is stored as `RECOVERY` with a plan because guest filesystem consistency is not guaranteed. Failure to confirm termination remains visible as `ERROR`; the process handle retains the recovery-required marker so a later exit callback cannot erase the recovery requirement.
8. If the store says a VM is active or in `ERROR` but no runtime process handle exists, a start/stop request moves it to `RECOVERY` and writes a recovery plan instead of launching a duplicate or falsely reporting `OFF`.

9. Startup admission, process registration and service closure share a linearization gate. Once service closure begins, no later start can register a backend process. A process created by a startup already in flight must either register before the shutdown snapshot or fail registration and be terminated by its startup rollback.
10. Service destruction persists `RECOVERY` and marks each handle recovery-required before requesting process termination. Termination and sidecar cleanup run on a bounded background pool so the Android main thread is not held waiting for backend exit. A process handle is removed only after exit is confirmed; a surviving process keeps its handle and sidecar references for later recovery.
11. Backend readiness failure uses the same bounded process terminator. If that process survives, fallback is blocked, the handle is retained, and the VM is marked `RECOVERY`; a second bounded termination attempt runs independently.

## Concurrency and resource isolation

A shared worker pool backs one serial queue per VM. This avoids a global lock around long QMP/crosvm operations: VM-A can progress independently of VM-B, while each VM's own lifecycle transitions remain ordered. Queue failures are reported and do not strand later commands for that VM.

Process-monitoring and process-wait threads remain separate from the lifecycle queues. Monitoring is read/observe-only; all terminal state changes occur through the queue. All process-local VmStore read/modify/write mutations share one lock across every store instance. Creation, clone, import, delete and runtime service updates now commit against the latest collection; process-exit finalization uses an atomic per-VM update rather than saving an older list snapshot. Cold clone/snapshot/restore/export operations hold the same store transaction for the full disk access, so lifecycle startup admission cannot interleave between an OFF check and the disk operation. Exit classification also consults the handle's latest recovery marker and the service gate at finalization time, preventing a delayed callback from overwriting a recovery state during service destruction.

## Automated coverage

JVM tests assert:

- FIFO execution for commands with one VM ID;
- concurrent progress for distinct VM IDs;
- queue progression after a command fails;
- graceful exit without unnecessary escalation;
- escalation to forced termination when a backend does not exit;
- failure remains failure when the process is still alive after the final termination attempt;
- clean, unexpected, forced and delayed process exits are mapped to OFF, ERROR or RECOVERY by a pure policy covered with unit tests.

## Acceptance boundary

These tests validate deterministic software-side orchestration. They do not replace Android-device acceptance for guest shutdown behavior, service recreation, process death under memory pressure, thermal stress, or repeated VM start/stop cycles.
