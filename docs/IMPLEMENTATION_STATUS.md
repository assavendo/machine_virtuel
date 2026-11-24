# Implementation status against the source specification

A feature is COMPLETE only after implementation and validation. The source specification is 64 sections; this matrix deliberately distinguishes implementation from device proof.

## Full-system core — implemented

- Full-system VM boundary: CPU, RAM, UEFI/NVRAM, virtual disks, network, GPU, input and lifecycle.
- Pinned DroidVM-Prebuilts runtime, safe extraction and critical SHA-256 verification.
- QEMU ARM64 virt machine with KVM selection and TCG multi-thread fallback.
- Per-VM persistent raw disk and qemu-img primitives for qcow2/vhdx/vdi/vmdk.
- UEFI pflash + writable NVRAM.
- Optional installer ISO.
- VirtIO block, network, GPU, multitouch, keyboard, RNG.
- QEMU XHCI USB controller and UTC RTC.
- 9p shared directory with optional read-only mode.
- QMP shutdown/pause/resume.
- Cold disk/NVRAM snapshots.
- Embedded RFB/VNC framebuffer + pointer/keyboard events.
- Foreground service lifecycle with truthful RUNNING/OFF/ERROR state.
- Root-aware process launcher and TAP interface preparation.
- NAT port forwarding.
- GPU backend modes: software, VirGL, Gfxstream/rutabaga selection.
- VirtIO sound + Android AAudio QEMU backend path.
- HypervisorBackend abstraction with lifecycle/resize/device/metrics contract.
- crosvm backend selection/launch foundation for KVM, Gunyah, GenieZone and Halla.

## Implemented but device-dependent

- KVM acceleration depends on /dev/kvm permissions and kernel/vendor configuration.
- Gunyah/GenieZone/Halla depend on vendor hypervisor device nodes and permissions.
- TAP networking depends on root and Android kernel tun support.
- VirGL/Gfxstream depends on the packaged renderer, EGL/Vulkan availability and device driver behavior.
- AAudio depends on the QEMU Android audio build and host audio stack.

## Still requiring dedicated backend work or on-device proof

- crosvm-native Android display/input bridge.
- crosvm full network bridge/NAT orchestration and crosvm audio UI path.
- virtio-fs shared folders in addition to 9p.
- USB host device passthrough and Android permission handling.
- TPM 2.0 / swtpm integration.
- Suspend-to-disk and live RAM/device-state migration snapshots.
- Direct Vulkan shared-buffer display into Android Surface without VNC.
- x86_64 QEMU runtime packaged for ARM64 hosts.
- Persistent x86 DynaRec translation cache, tiered JIT and profile database.
- Thermal-aware scheduler with per-core affinity and sustained-load control.
- Device profile database and multi-device compatibility matrix.
- Complete benchmark suite with measured comparison against Limbo/QEMU/crosvm/DroidVM.
- Live VM clone/export/import and recovery package UI.
- Windows ARM64/x86 acceptance suite and driver/UEFI coverage.
- Android guest acceptance suite.

## MVP conclusion

The ARM64 full-system QEMU path is now a genuine VM engine rather than an application emulator. The remaining list is intentionally not hidden: those items require either additional native backend engineering or physical-device validation before they can be called complete.
