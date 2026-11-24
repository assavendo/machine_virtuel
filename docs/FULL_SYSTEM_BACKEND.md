# Full-system backend integration

PhoenixVM is a real system VM platform. The guest owns CPU state, RAM, firmware, storage, network, graphics, audio and input. Wine/Winlator/Box64/FEX are optional compatibility components only; they are not the VM.

## Backend stack

### QEMU ARM64 reference path
- ARM64 virt machine with GIC v3 and high memory.
- KVM when /dev/kvm is usable.
- TCG thread=multi fallback.
- UEFI pflash + per-VM NVRAM.
- Raw or qemu-img managed disk formats.
- VirtIO block/network/GPU/input/RNG.
- XHCI USB controller and RTC.
- Optional 9p shared directory.
- NAT + host port forwarding or root TAP.
- VirtIO sound using the Android AAudio QEMU audio backend.
- Software, VirGL and Gfxstream/rutabaga GPU modes.
- QMP lifecycle control.
- Local VNC/RFB display baseline.

### crosvm hardware path
The orchestrator recognizes KVM, Gunyah, GenieZone and Halla backends. These are real VMM paths, not application compatibility layers. Display, device and network bridges are progressively separated because the Android permission model differs by vendor hypervisor.

## Runtime integrity

The runtime is pinned to DroidVM-Prebuilts commit c4998e150a08f0e60d141591fd63ea0010eb4f75. First boot streams the compressed prebuilt, rejects unsafe tar paths, verifies critical SHA-256 values, installs into app-private storage and records the revision marker.

## Guest architecture boundary

The shipped prebuilt contains the ARM64 system QEMU binary. The command builder already has an explicit x86_64 binary slot, but the current runtime package does not contain that binary. Consequently an x86_64 guest fails closed with a precise runtime error instead of silently emulating it incorrectly.

## Safety and lifecycle

RUNNING is only persisted after the actual backend process survives launch. Process exit updates OFF/ERROR. QMP performs orderly powerdown and pause/resume for QEMU. TAP devices are deleted only when PhoenixVM created them.

## High-performance path

The next native acceleration step is direct virtio-gpu + gfxstream/Venus shared-buffer presentation into an Android Surface, bypassing VNC/RFB. The current VNC/RFB client is intentionally kept as a compatibility baseline.

## Verification boundary

The repository can validate code and packaging in GitHub Actions, but actual KVM/Gunyah/GenieZone/Halla access, GPU driver behavior, AAudio, Windows boot, Android guest boot and sustained thermal performance require real Android hardware validation.
