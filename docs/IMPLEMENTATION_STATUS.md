# PhoenixVM — implementation status against the source cahier

A requirement is marked complete here only when the repository contains an actual implementation or a deterministic software-side mechanism. Physical acceptance is separated explicitly.

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

### Memory / scheduling / performance
- P/E-core topology discovery.
- Multi-VM resource admission and global scheduler policy.
- Thermal/memory-aware adaptive performance decisions.
- Balloon-based memory relief through QMP when explicitly enabled.
- Host CPU, memory, thermal, frequency, I/O, network and pressure telemetry.
- 1/5/30 minute metric history.
- Benchmark model and benchmark engine foundation; sustained-run catalog is defined.

### Graphics / input / audio
- VirtIO GPU software, VirGL and Gfxstream/rutabaga command modes with VNC/RFB display baseline.
- Dynamic resolution/fit behavior and configurable edge swipe, pinch and three-finger gestures.
- VirtIO input devices, keyboard mapping and touch/mouse input path.
- VirtIO sound with Android AAudio backend selection.
- Android Surface/shared-buffer/Vulkan end-to-end presentation remains a physical/device integration item.

### Storage / snapshots / packages
- RAW/qcow2/vhdx/vdi/vmdk creation and qemu-img operations for resize/convert/clone/check/compact.
- Per-VM UEFI/NVRAM and firmware provenance metadata.
- Cold snapshots with SHA-256 manifest, protection, retention and restore.
- VM package import/export with manifest and SHA-256 verification.
- Optional Ed25519 package signature verification when signature/public-key sidecars are supplied.
- Versioned VM schema migration and configuration backup.

### Network / sharing / peripherals
- NAT and root/TAP networking, port forwarding, per-VM deterministic MAC, hostname/DNS/MTU policy.
- VirtIO network controller and explicit network validation.
- VirtIO-FS path when verified virtiofsd is present; 9p/VirtFS fallback otherwise.
- XHCI virtual USB controller.
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

The x86 translation tier and persistent-cache work in this app is policy/metadata around QEMU TCG; it does not claim to replace QEMU's internal translator with a new native JIT.

## Validation boundary

GitHub CI can validate source, unit tests, formatting, APK assembly and packaging. It cannot prove vendor hypervisor access, GPU driver behavior, audio behavior, guest boot, thermal sustainability or Android permission behavior without a real device.
