# Implementation status against the source specification

A feature is COMPLETE only after implementation and validation. This matrix distinguishes code implementation from device proof and from third-party runtime availability.

## Implemented in code

### VM model and orchestration
- Persistent VM schema with ID, name, description, OS/version, architecture, machine type, firmware/NVRAM, disk format/size, RAM, vCPU, GPU/audio/network/share configuration, security/profile/mode fields, boot order, snapshot policy, monitoring and cache level.
- Four modes: AUTO, PERFORMANCE, COMPATIBILITY, EXPERT.
- Nine requested performance presets: Compatibility, Balanced, Performance, Ultra Performance, Battery Saver, Gaming, Office, Server, Development.
- VM states: OFF, STARTING, RUNNING, PAUSED, SUSPENDED, STOPPING, ERROR, RECOVERY.
- Backend orchestration with KVM, Gunyah, GenieZone, Halla, AVF capability gating and QEMU TCG fallback.
- Explicit fail-closed handling when an optional backend or runtime component cannot actually execute.
- Persistent VM event model and stable engine API.

### Full-system execution
- ARM64 QEMU full-system path with virt machine, GIC v3, UEFI pflash/NVRAM, VirtIO block/network/GPU/input/RNG, XHCI and RTC.
- x86_64/x86-32 command path with q35 + QEMU TCG/MTTCG, ready to use when the optional Android ARM64 QEMU x86 runtime is provisioned.
- NAT, port forwarding and root/TAP orchestration.
- 9p shared-folder fallback.
- VirtIO-FS path when a verified virtiofsd is provisioned, with vhost-user memory-sharing configuration.
- Optional TPM 2.0 path using verified swtpm, with architecture-correct QEMU TPM device.
- QMP lifecycle control and foreground-service lifecycle.
- Real process lifecycle determines RUNNING/OFF/ERROR; the app does not mark a VM running merely because a command was assembled.

### Storage and state
- RAW/qcow2/vhdx/vdi/vmdk creation primitives.
- resize/convert/clone/check/compact through qemu-img.
- cold snapshots with SHA-256 manifests, protection, retention and atomic restore.
- VM package export/import with manifest, checksums, firmware/NVRAM and optional snapshots.
- Safe ZIP extraction and path traversal protection.
- Crash recovery plan preserving logs and user disks.

### Performance and translation policy
- CPU topology detection for performance/efficiency capacity.
- Global multi-VM scheduler policy.
- Nine performance profiles with resource tuning.
- Adaptive performance decisions using thermal and memory pressure.
- Real-time host metrics: process/system CPU, memory, thermal state, temperature, frequency, I/O, network and memory-pressure telemetry.
- 1/5/30-minute metric history abstraction.
- Translation COLD/WARM/HOT/VERY_HOT policy and persistent cache metadata with fingerprints, translator version, host features, architecture and cache levels.
- Native Rust backend-selection and translation-tier policy modules with unit tests.
- The cache is an orchestration/metadata layer around QEMU TCG; it does not falsely claim to replace QEMU's internal translator.

### Security, images and diagnostics
- Canonical path validation and allowed-root guards.
- Explicit security policy flags for root backends, host-path sharing, TAP, package/image verification and per-VM network isolation.
- SHA-256 image verification and image metadata model.
- Verified runtime extraction with path traversal protection and critical binary/firmware hashes.
- Optional runtime status for x86 QEMU, virtiofsd, swtpm, gfxstream and virglrenderer.
- Diagnostic ZIP export including host data, runtime checks and logs.
- ERROR/WARN/INFO/DEBUG/TRACE VM logger.

### Benchmarking and tests
- CPU, memory and sustained host benchmark primitives.
- Unit tests for backend selection, VM spec validation, scheduler, image verification, security guards and QEMU command generation.
- Rust unit tests for backend selection and translation tiers.
- QEMU command tests cover ARM64, x86_64, NAT/forwarding and Gfxstream.
- CI builds the native core, runs tests/lint and assembles the Android debug APK.

## Implemented but dependent on verified runtime/device capabilities

- KVM, Gunyah, GenieZone, Halla and AVF require real device/vendor/system exposure and permissions.
- Gfxstream/VirGL acceleration depends on the packaged renderer and Android GPU driver path.
- VirtIO-FS depends on a compatible Android virtiofsd build and usable host permissions/user-namespace or equivalent isolation.
- TPM depends on a verified Android swtpm build.
- TAP/bridge networking depends on root and kernel networking support.
- AAudio audio depends on the actual QEMU Android audio backend and device audio stack.
- x86/x86_64 full-system execution is code-ready but the currently pinned DroidVM-Prebuilts bundle does not contain qemu-system-x86_64; the repository now contains a reproducible QEMU 11.1.3 runtime-build recipe.

## Still requires on-device implementation/acceptance before being called complete

- Direct Android Surface presentation with shared GPU buffers and Vulkan/Venus end-to-end; current display baseline remains local VNC/RFB.
- Complete crosvm display/input/audio/network bridges for every vendor backend.
- USB host passthrough and Android permission lifecycle.
- Live suspend/resume with complete device/RAM state, as opposed to cold disk/NVRAM snapshots.
- Full Windows ARM64/x86 acceptance, VirtIO driver coverage and TPM/Secure Boot validation.
- Android guest acceptance suite with system/userdata/vendor/kernel/bootloader/ADB.
- Device capability database covering Snapdragon, Dimensity, Exynos, Tensor and other ARM64 platforms.
- Measured baseline suite against Limbo/QEMU/crosvm/DroidVM.
- 100-boot/restore stress, 30/60-minute thermal, GPU, I/O and background-interruption stability runs.
- Hardware security/sandbox fuzzing and supply-chain verification on representative devices.
- Release-grade package migration/signing/update policy.

## Honest status rule

The project must not advertise hardware acceleration, GPU acceleration, x86 support, TPM, VirtIO-FS, AVF, native Surface presentation or performance gains unless the corresponding runtime component and physical-device validation are present. Unsupported paths fail closed and report why.
