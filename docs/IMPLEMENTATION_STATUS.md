# PhoenixVM — implementation status against the source cahier

A requirement is marked complete here only when the repository contains an actual implementation or a deterministic software-side mechanism. Physical acceptance is separated explicitly.

## Latest implementation increment — serialized VM lifecycle and confirmed process shutdown

Lifecycle intents are now FIFO-serialized per VM while separate VMs remain independently schedulable. Process-exit callbacks are routed back through the same per-VM queue and use handle-identity removal, preventing a delayed callback from clearing a newer run. Pause/resume/reboot validate the persisted state, clean stop escalates through bounded graceful/destroy/force-kill stages, and the VM is only persisted as OFF after process termination is confirmed. Missing runtime handles are surfaced as RECOVERY rather than being represented as a successful stop. If an attempted stop cannot confirm termination before its bounded deadline, the running handle retains a recovery-required marker so a later process-exit callback cannot misclassify the VM as cleanly stopped. Unit tests cover per-VM FIFO order, cross-VM concurrency, queue failure recovery, process-termination escalation and pure exit classification. A VM that requires destroy/force escalation is marked RECOVERY, while a process already dead before the stop request remains an unexpected exit. A new start is admitted only from OFF; stale active/error states without a runtime handle are converted to RECOVERY. Startup admission, process registration and service closure share a linearization gate; service destruction marks active guests for recovery immediately and moves bounded process termination/cleanup to background workers, retaining a live handle if termination cannot be confirmed. Backend readiness failures use the same bounded terminator and prohibit fallback if the old process survives. Exit finalization consults the latest handle recovery marker and closed service gate. Service-owned VM store writes are serialized across VMs. Physical long-run/restart acceptance remains separate.

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
- Multi-VM resource coordination is reevaluated during VM execution with thermal/memory-aware recommendations and QMP balloon recovery when supported.
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
