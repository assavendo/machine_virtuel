# PhoenixVM — implementation status against the source cahier

## Latest implementation increment — CPU topology, runtime affinity and telemetry

Section 8 software work now filters CPU topology to online CPUs intersected with the calling process's effective cpuset (`/proc/self/status`, with cgroup fallbacks). P/E classification uses heterogeneous `cpu_capacity` when available, then maximum frequency; homogeneous or unavailable topology remains `UNKNOWN` rather than being guessed. The scheduler allocates at least one vCPU per active VM, distributes remaining physical slots by VM priority/profile, balances thread assignments and prefers performance or efficiency cores accordingly. At VM startup the selected effective vCPU count is bounded by the user-requested count, current CPU slots across active VMs and thermal policy; the original requested count remains persisted unchanged. Once the guest is running, scheduler refreshes only change thread affinity and do not attempt QMP vCPU hotplug/unplug. Every QMP-discovered vCPU TID is round-robin constrained to the assigned allowed CPU set even when there are more vCPU threads than scheduler slots. An empty/untrustworthy allowed CPU mask yields no scheduler recommendation, preserving the validated runtime fallback rather than forcing one vCPU. The policy maps VM priority into Android/Linux nice values 0..10, avoiding requests for privileged negative nice values on ordinary non-root app processes. It emits the actual permitted CPU IDs.

The runtime resolves QEMU/crosvm process IDs by matching executable identity and the VM-specific QMP/control socket, avoiding accidental targeting of a `su` launcher or another VM's backend. It queries QEMU vCPU TIDs (`query-cpus-fast`) and I/O-thread TIDs (`query-iothreads`). Through the Rust JNI core, it attempts Linux/Android `sched_setaffinity` on backend-process threads; individual vCPU threads receive a single assigned CPU and QMP-identified I/O threads receive the VM's assigned CPU set plus a lower scheduling priority. Other backend threads remain in the VM CPU set but are not guessed to be GPU or audio threads. Priority and affinity failures are counted and logged but never prevent VM startup or continued operation. crosvm processes use the same process-scoped CPU-set containment when their control socket is present, without pretending that QMP per-vCPU IDs are available.

Profiling now distinguishes backend-process CPU/RSS and block I/O (`/proc/<pid>/stat`, `status` and `io`) from system-wide CPU/disk/network metrics, separates system CPU busy time from iowait, records the wall-clock sampling interval and preserves those values in 1/5/30-minute metric windows. Backend CPU percent is null until a valid per-process baseline delta exists, and backend RSS/CPU remain null if the exact QEMU/crosvm PID cannot be resolved; no missing process metric is falsely published as zero. An opt-in Linux QEMU Guest Agent channel is exposed in the creation wizard and persisted VM schema/package configuration; QEMU provides a private UNIX socket chardev connected through `virtio-serial`. The runtime performs `guest-sync-delimited` and `guest-get-cpustats`, computes per-CPU cumulative deltas, and records guest CPU busy time, guest I/O-wait, IRQ/softirq percentage and the sampled guest CPU milliseconds. The guest must install and run a compatible `qemu-guest-agent`; `guest-get-cpustats` is available since QEMU 7.1. Missing, old or unsupported agents, as well as Linux kernels that omit optional `iowait`, `irq`, `softirq` or `steal` counters, leave the complete guest CPU sample unavailable rather than substituting zero. Guest CPU time and guest RAM stay null when no trustworthy guest source exists; they are not reported as zero. Translation/cache timing is surfaced only when profile counters exist. These counters are not a QEMU-internal TCG instrumentation interface, and this application does not claim to observe guest GPU wait or guest CPU stalls without a reliable backend/guest counter. Guest IRQ/softirq are populated only when the optional compatible Linux QEMU Guest Agent responds.

Automated tests cover cpulist parsing, online/cpuset intersection, safe P/E classification, scheduling assignments, nice-value mapping, JNI CPU-mask boundaries, `/proc/stat` parsing, process I/O parsing, QGA Linux counter parsing/deltas and overflow rejection, guest-agent VM config/package round-trip, QEMU virtio-serial command generation, guest metric windows and deterministic thread-placement policy. The native syscall's successful application, OEM cpuset behavior, QEMU thread classification, thermal effectiveness and real performance impact remain deferred physical acceptance. QEMU TCG already owns executable Translation Blocks and its native JIT/chaining behavior; the PhoenixVM-specific persistent cache and tiered-compilation requirements are handled separately under sections 9 and 10.

## Latest implementation increment — guided full-system VM creation

The create flow is now a real seven-page A–G wizard rather than one long form: Architecture, Guest OS/media, Resources, GPU/display, Network, Host/guest sharing, and Optimization/security. It exposes a progress indicator and Previous/Next navigation; the last page changes Next to Create. All seven pages keep their form state while switching, and Android's ADB settings are grouped with guest OS/media. File selection temporarily closes the dialog, copies the selected artifact, and reopens the same wizard page with the updated draft; cancel and import-error paths also return to the previously active page. Validation errors route to the owning page through the pure, unit-tested VmCreationWizardStep policy. Guest/architecture combinations are constrained; creation collects RAM, vCPU, virtual disk size/format, GPU policy, disabled/NAT/TAP networking and port forwards (hostfwd is rejected outside NAT rather than silently omitted), a per-VM shared directory, optional multi-selected transient shared files, and adaptive/performance/battery-saver profiles. ISO, existing disk, kernel, initrd, DTB and Android system/vendor/userdata artifacts are copied from Android's document provider into the VM's private directory using a temporary file, a 64 MiB free-space reserve and same-filesystem atomic rename. Multi-file share imports roll back the entire batch if any item fails. Imported disk containers are checked using non-executing signatures for QCOW2/VHDX/VDI/VMDK; untrusted image metadata is not parsed at creation time. VM creation validates unique name/ID, disk capacity bounds, media paths, shared directories, guest/architecture compatibility, network forwarding and aggregate RAM admission before publishing the record. When requested, the initial cold snapshot is completed before insertion, so the new VM cannot start while its initial disk snapshot is being copied. Store mutations are serialized by one process-wide transaction lock across every VmStore instance. New VM creation, cloning and package import append against the latest store snapshot with uniqueness and RAM admission rechecked under the same lock; process-exit finalization uses an atomic update rather than a stale list rewrite. VM deletion removes the record and managed artifacts under the same lock and only accepts confirmed OFF state. Legacy manager shortcuts that could directly persist STARTING or OFF without the runtime admission/termination contract have been removed. The internal VmEngine API now delegates start directly to VmRuntimeService and does not publish a pre-admission state. Clone, cold snapshot, snapshot restore, package export, ISO/audio/USB edits, optimization and deletion use a process-wide per-VM exclusive lock. Startup admission takes the same lock, rereads the latest stored configuration after waiting for it, validates it and uses that exact snapshot for runtime/USB/TAP setup and launch, then persists STARTING before releasing the lock. No stale pre-lock VM configuration can be launched. The lock is scoped to one VM ID: other VMs can continue lifecycle transitions while a large snapshot or clone is in progress. Collection read/modify/write commits remain briefly serialized by the separate process-wide store mutation lock. Startup RAM admission and the OFF-to-STARTING state transition are one atomic store mutation, serialized against VM creates/imports and other starts. A concurrency regression test verifies that a lifecycle update cannot be lost behind another VM insertion and that duplicate names cannot be committed concurrently. Unit tests cover the pure guest-choice, port-forward, shared-directory and disk-header policies. This remains software-side coverage: guest installation/boot, real bridge/root networking, graphics acceleration, x86 extension availability and hardware/device compatibility are not claimed as accepted.

A requirement is marked complete here only when the repository contains an actual implementation or a deterministic software-side mechanism. Physical acceptance is separated explicitly.

## Latest implementation increment — serialized VM lifecycle and confirmed process shutdown

Lifecycle intents are now FIFO-serialized per VM while separate VMs remain independently schedulable. Process-exit callbacks are routed back through the same per-VM queue and use handle-identity removal, preventing a delayed callback from clearing a newer run. Pause/resume/reboot validate the persisted state, clean stop escalates through bounded graceful/destroy/force-kill stages, and the VM is only persisted as OFF after process termination is confirmed. Missing runtime handles are surfaced as RECOVERY rather than being represented as a successful stop. If an attempted stop cannot confirm termination before its bounded deadline, the running handle retains a recovery-required marker so a later process-exit callback cannot misclassify the VM as cleanly stopped. Unit tests cover per-VM FIFO order, cross-VM concurrency, queue failure recovery, process-termination escalation and pure exit classification. A VM that requires destroy/force escalation is marked RECOVERY, while a process already dead before the stop request remains an unexpected exit. A new start is admitted only from OFF; stale active/error states without a runtime handle are converted to RECOVERY. Startup admission, process registration and service closure share a linearization gate; service destruction marks active guests for recovery immediately and moves bounded process termination/cleanup to background workers, retaining a live handle if termination cannot be confirmed. Backend readiness failures use the same bounded terminator and prohibit fallback if the old process survives. Exit finalization consults the latest handle recovery marker and closed service gate. All process-local VmStore read/modify/write mutations now share a single transaction lock across service, manager and package-import instances. Exit finalization updates the latest stored record atomically instead of rewriting a previously read collection. Physical long-run/restart acceptance remains separate.

## Latest implementation increment — backend-aware QEMU sidecars

QEMU auxiliary runtime processes are now selected by explicit backend capabilities rather than by the `QEMU_TCG` enum value. Both QEMU/KVM and QEMU/TCG can start the verified `virtiofsd` and `swtpm` helpers before the VM process. The crosvm command builder rejects a requested TPM instead of silently omitting it; the runtime can then try the next eligible QEMU fallback. Regression tests cover QEMU/KVM, QEMU/TCG and crosvm capability declarations and the crosvm TPM guard.

## Latest implementation increment — native orchestration policy

The Android orchestrator now crosses a real JNI boundary into the Rust policy core for the initial backend decision. Kotlin still validates the returned backend against the live capability set before accepting it, so a stale or unavailable native decision cannot cause an invalid backend launch.

The native core now exposes:
- backend selection for ARM64 guests across KVM, Gunyah, GenieZone, Halla, AVF and QEMU TCG;
- x86/x86_64 full-system selection as QEMU TCG;
- thermal-aware conservative fallback;
- translation tier classification COLD/WARM/HOT/VERY_HOT.

The native boundary is fail-safe:
- missing native library -> deterministic Kotlin fallback;
- JNI call failure -> QEMU_TCG fallback for the native policy result;
- expert-mode explicit request remains subject to the Android-side availability guard.

## Software work completed

### VM architecture and lifecycle
- Persistent VM model with architecture, guest metadata, machine type, UEFI/NVRAM, storage, RAM, vCPU, GPU, audio, network, sharing, security, profiles and monitoring.
- AUTO, PERFORMANCE, COMPATIBILITY and EXPERT modes.
- Compatibility, Balanced, Performance, Ultra Performance, Battery Saver, Gaming, Office, Server and Development profiles.
- OFF/STARTING/RUNNING/PAUSED/SUSPENDED/STOPPING/ERROR/RECOVERY state model.
- Create, clone, start, clean stop, force stop, pause, resume, reboot, delete, snapshot, restore, export, import, diagnostics and recovery APIs/UI.
- Stable internal VM/HOST/ENGINE API plus event bus foundation.

### CPU / hypervisor / orchestration
- ARM64 QEMU full-system reference backend with KVM when available and TCG fallback.
- Host-aware selection foundation for KVM, Gunyah, GenieZone, Halla, AVF and QEMU TCG.
- Explicit fallback and fail-closed behavior when a backend/runtime is not really available.
- x86_64 and x86/i386 full-system command paths use QEMU system emulators, never Wine/Winlator/user-mode compatibility.
- QEMU TCG MTTCG policy and translation COLD/WARM/HOT/VERY_HOT profiling metadata.
- Persistent translation-cache metadata keyed by VM/guest/translator/host features and cache level.
- Rust native orchestration policy is now invoked from the Android path through JNI, with Kotlin availability guards.
- Translation profiling uses the Rust native tier decision when available, with a deterministic Kotlin fallback.
- Persistent translation profile metadata now records execution count, cache hits/misses and cumulative translation time while remaining compatible with older profile records.
- Multi-VM resource coordination is reevaluated during VM execution with thermal/memory-aware recommendations and QMP balloon recovery when supported. RAM admission conservatively retains commitments for STOPPING, RECOVERY and ERROR VMs until their state is reconciled, because a delayed or failed termination may still own a live process.
- CPU reductions that cannot be hot-applied safely are explicitly marked as next-start recommendations rather than being falsely reported as live changes.

### Memory / scheduling / performance
- P/E-core topology discovery.
- Multi-VM resource admission and global scheduler policy.
- Thermal/memory-aware adaptive performance decisions.
- Balloon-based memory relief through QMP when explicitly enabled.
- Host CPU, memory, thermal, frequency, I/O, network and pressure telemetry.
- 1/5/30 minute metric history.
- Runtime FPS measured from the actual RFB frame stream.
- vCPU host-thread utilization measured from QMP `query-cpus-fast` thread IDs and `/proc` CPU time.
- Persistent translation cache hit rate and average translation time are surfaced when profiles exist.
- Guest CPU time, guest RAM, GPU utilization/memory and AAudio underruns remain explicitly unknown unless a backend/guest exposes trustworthy counters; they are never replaced with fabricated zeros.
- Benchmark model and benchmark engine foundation; sustained-run catalog is defined.

### Graphics / input / audio
- VirtIO GPU software, VirGL and Gfxstream/rutabaga command modes with VNC/RFB display baseline.
- GPU capability reporting distinguishes host Vulkan availability from actually verified VM GPU acceleration; unverified paths are never advertised as accelerated.
- Dynamic resolution/fit behavior and configurable edge swipe, pinch and three-finger gestures.
- VirtIO input devices, keyboard mapping and touch/mouse input path.
- VirtIO sound with Android AAudio backend selection.
- Audio policy supports frequency, channels, buffer size, timer period and mute for QEMU AAudio, with validation and persisted settings.
- crosvm fails closed when the requested audio configuration cannot be guaranteed by its current Android AAudio path.
- Runtime audio monitoring records underrun/overrun/XRUN events only when the backend exposes them in logs; configured buffer latency is reported separately from measured AV synchronization.
- End-to-end AV synchronization remains a physical/guest telemetry acceptance item and is never reported as measured without a trustworthy guest/audio clock path.
- Android Surface/shared-buffer/Vulkan end-to-end presentation remains a physical/device integration item.

### Storage / snapshots / packages
- RAW/qcow2/vhdx/vdi/vmdk creation and qemu-img operations for resize/convert/clone/check/compact.
- Per-VM UEFI/NVRAM and firmware provenance metadata.
- x86_64 Secure Boot is implemented as a verified optional path using the pinned Debian OVMF Secure Boot code + Microsoft-keyed VARS template, with SMM/pflash protection, per-VM Secure Boot NVRAM and fail-closed runtime validation. ARM64 Secure Boot remains unavailable until a verified ARM64 Secure Boot firmware/variable-service runtime is provisioned.
- Cold snapshots with SHA-256 manifest, protection, retention and restore.
- VM package import/export with manifest and SHA-256 verification.
- Optional Ed25519 package signature verification when signature/public-key sidecars are supplied.
- Versioned VM schema migration and configuration backup.

### Network / sharing / peripherals
- NAT and root/TAP networking, port forwarding, per-VM deterministic MAC, hostname/DNS/MTU policy.
- VirtIO network controller and explicit network validation.
- VirtIO-FS path when verified virtiofsd is present; 9p/VirtFS fallback otherwise.
- XHCI virtual USB controller.
- Per-VM host USB passthrough configuration with Android UsbManager permission lifecycle, explicit device matching, hub rejection, bus/address resolution, direct-node/root access gating and QEMU `usb-host` launch translation.
- USB passthrough is fail-closed: permission alone is not treated as sufficient host access, and non-QEMU/crosvm backends are not allowed to silently receive host USB devices.
- Explicit host USB passthrough policy model that never grants devices globally by default.
- TPM 2.0 orchestration through verified swtpm when present.

### Guests / compatibility / ecosystem
- Linux ARM64 remains the primary supported target in software.
- Windows ARM64/x86_64 guest specification and validation model.
- Android ARM64 guest specification for system/userdata/vendor/kernel/bootloader/ADB/display/network.
- BSD, legacy x86 and custom guest catalog.
- Device profile database structure for Qualcomm, MediaTek, Exynos, Tensor and other ARM64 hosts.

### Security / diagnostics / licensing
- Canonical path and allowed-root checks.
- Runtime critical binary/firmware SHA-256 verification.
- Security audit findings model.
- Ed25519 signature verification utility.
- Per-VM structured logging ERROR/WARN/INFO/DEBUG/TRACE.
- Diagnostic ZIP export and developer reports.
- Third-party component provenance/license tracking documentation.
- No software path substitutes a compatibility layer for a VMM.

### UI / developer mode / CI
- VM dashboard with lifecycle controls, ISO import, snapshots, clone, optimization advice, package import/export, diagnostics and developer reporting.
- Configurable gesture settings UI.
- Developer benchmark/metrics report foundation.
- GitHub CI for Rust format/tests and Android unit tests/lint/build.
- Additional quality/security workflow and license tracking checks.

## Explicitly not marked complete yet — requires runtime availability or physical acceptance

1. Actual KVM/Gunyah/GenieZone/Halla/AVF execution on representative Android devices.
2. Actual QEMU x86_64/i386 Android runtime build and execution on the target phone; the pinned DroidVM-Prebuilts bundle does not ship these binaries.
3. virtiofsd runtime build/start on Android.
4. swtpm runtime build/start on Android.
5. Full crosvm display/input/audio/network device bridges across vendor hypervisors.
6. Direct Android Surface + Vulkan/shared-buffer presentation end-to-end.
7. USB host passthrough including Android permission lifecycle.
8. Windows ARM64/x86 installation and VirtIO driver acceptance.
9. Android guest boot acceptance including kernel/bootloader/system/vendor/userdata/ADB.
10. Actual audio latency/AV-sync acceptance.
11. Representative CPU/GPU/I/O benchmark runs and +20% claims.
12. Limbo/QEMU/crosvm/DroidVM baseline comparison under equivalent settings.
13. 30/60 minute sustained thermal tests, 100 boot/reboot/restore stability tests and screen-off/background/interruption tests.
14. Fuzzing/path-traversal/sandbox/security tests on physical Android builds.
15. Complete Snapdragon/Dimensity/Exynos/Tensor device matrix with measured results.
16. Release-grade signing/update/rollback testing.

## Technical limits

The application must not report hardware acceleration, native Surface presentation, TPM, VirtIO-FS, x86 support or performance gains unless the corresponding real runtime component has started successfully. Software-side capability detection is not proof of physical availability.

The x86 translation tier and persistent-cache work in this app is policy/metadata around QEMU TCG; it does not claim to replace QEMU's internal translator with a new native JIT. QEMU TCG remains authoritative for executable translation blocks; PhoenixVM only classifies activity, persists compatible metadata and tunes the QEMU TB cache size.

## Validation boundary

GitHub CI can validate source, unit tests, formatting, APK assembly and packaging. It cannot prove vendor hypervisor access, GPU driver behavior, audio behavior, guest boot, thermal sustainability or Android permission behavior without a real device.
