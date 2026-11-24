# PhoenixVM / machine_virtuel

PhoenixVM is an Android full-system virtual machine engine, not a Wine/Winlator application launcher.

## Architecture

- Real full-system QEMU AArch64 reference backend.
- QEMU x86_64/i386 full-system command path for an Android ARM64 x86 runtime when provisioned.
- KVM, Gunyah, GenieZone, Halla and AVF capability-aware orchestration.
- UEFI firmware and per-VM NVRAM.
- Persistent RAW/qcow2/vhdx/vdi/vmdk storage.
- VirtIO block/network/GPU/input/RNG, XHCI and RTC.
- VirtIO-FS with 9p fallback when the verified runtime permits it.
- Optional TPM 2.0 through verified swtpm.
- QMP lifecycle control and foreground service.
- VNC/RFB display baseline.
- Adaptive scheduler, thermal/memory advisor, metrics history and translation-cache policy.
- VM package export/import, checksummed cold snapshots and recovery diagnostics.
- Kotlin API surface plus a native Rust policy core.

## Important truth boundary

A backend is considered usable only when its real executable/device interface is present and can start the VM. PhoenixVM never reports fake KVM, fake GPU acceleration, fake TPM or fake x86 support.

The currently pinned DroidVM-Prebuilts bundle does not contain qemu-system-x86_64, virtiofsd or swtpm. The repository therefore contains explicit optional-runtime provisioning/build paths for those components.

## Build

GitHub Actions checks out the pinned runtime submodule, builds the Rust native core, runs unit tests/lint and assembles the Android debug APK.

See:
- docs/IMPLEMENTATION_STATUS.md — source-specification status matrix.
- docs/FULL_SYSTEM_BACKEND.md — backend architecture.
- docs/X86_FULL_SYSTEM_RUNTIME.md — x86 runtime provisioning.
- THIRD_PARTY/DroidVM-Prebuilts.txt — runtime provenance/licensing.
