# Implementation status against the source specification

A feature is COMPLETE only after implementation and validation. No feature is marked complete merely because a class or UI placeholder exists.

## Full-system core

- Real full-system VM boundary: IMPLEMENTED.
- Pinned full-system runtime (QEMU/crosvm/UEFI/VirtIO libraries): IMPLEMENTED.
- Safe runtime extraction and critical SHA-256 verification: IMPLEMENTED.
- QEMU ARM64 virt machine: IMPLEMENTED.
- QEMU KVM selection: IMPLEMENTED in code; device validation pending.
- QEMU TCG multi-threaded translation: IMPLEMENTED.
- UEFI + per-VM writable NVRAM: IMPLEMENTED.
- Persistent raw disk: IMPLEMENTED.
- qcow2/vhdx/vdi/vmdk creation/conversion primitives: IMPLEMENTED in qemu-img layer; UI wiring pending.
- ISO attachment: IMPLEMENTED.
- VirtIO block/network/GPU/tablet/keyboard/RNG devices in QEMU path: IMPLEMENTED.
- Virtual USB controller: IMPLEMENTED in QEMU path; host USB passthrough pending.
- RTC: IMPLEMENTED.
- 9p shared directory: IMPLEMENTED in QEMU command path; guest mount UX pending.
- QMP powerdown/pause/resume: IMPLEMENTED.
- Cold disk/NVRAM snapshots: IMPLEMENTED; live RAM-state snapshots pending.
- Embedded raw-RFB/VNC display/input baseline: IMPLEMENTED.
- Foreground VM runtime service: IMPLEMENTED.
- Root-aware process launcher: IMPLEMENTED.
- crosvm KVM/Gunyah/GenieZone/Halla selection and launch foundation: IMPLEMENTED.

## Still incomplete from the complete source specification

- crosvm Android-native display/input bridge.
- Root TAP/bridge networking and IPv6 bridge modes.
- virtio-snd with AAudio/Oboe host audio.
- Shared folders via virtio-fs in addition to 9p.
- USB host device passthrough and automatic permissions.
- TPM 2.0 integration.
- Suspend-to-disk / saved-memory state.
- Live VM snapshots with RAM/CPU/device state.
- High-performance Vulkan/gfxstream/Venus shared-buffer display path.
- Persistent x86 translation cache connected to a real DynaRec/JIT.
- Tiered JIT implementation and profiling database.
- Thermal-aware vCPU/p-core/e-core scheduler.
- Complete Windows ARM64/x86_64 boot and driver acceptance suite.
- Complete Android guest acceptance suite.
- Multi-device hardware test matrix.
- Sustained benchmark suite and measured proof of the requested 20% improvement.
- Complete image manager UI, clone/export/import packages and recovery workflows.

## MVP reality check

The repository now contains a genuine full-system QEMU backend with real guest CPU/RAM/UEFI/disk/network/GPU/input and an embedded display path.

It is not yet the final 100% source-spec product because the remaining peripherals, privileged Android integrations and performance systems above still require implementation and on-device validation.
