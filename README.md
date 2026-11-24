# PhoenixVM / machine_virtuel

PhoenixVM is an Android project for a real full-system virtual machine, not a Wine/Winlator application launcher.

The target architecture is:
- QEMU System AArch64 for the complete compatibility/reference path;
- KVM acceleration when the host exposes it;
- crosvm with KVM, Gunyah, GenieZone or Halla when the device exposes those hypervisors;
- UEFI firmware and per-VM NVRAM;
- persistent virtual disks;
- VirtIO block/network/GPU/input devices;
- QMP lifecycle control;
- embedded VNC display baseline;
- persistent translation-cache infrastructure for future x86 DynaRec integration.

## Important current scope

The QEMU path is now a real full-system execution path and can boot a guest from a disk/ISO when the host allows the packaged runtime to execute.

Android vendor permissions are a hard platform boundary: access to KVM/Gunyah/GenieZone/Halla nodes may require root or vendor/system privileges. PhoenixVM detects and reports the available backend rather than faking hardware acceleration.

The repository intentionally keeps an implementation-status matrix. A peripheral or optimization is not declared complete until it is implemented and validated on hardware.

## Build

GitHub Actions checks out the pinned DroidVM-Prebuilts submodule, builds the native Rust core, runs unit tests/lint, assembles the Android debug APK and uploads it as an artifact.

The prebuilt full-system runtime is third-party code. See THIRD_PARTY/DroidVM-Prebuilts.txt for provenance.
