# Full-system backend integration

PhoenixVM is a real system VM platform. The guest owns CPU state, RAM, firmware, storage, network, graphics, audio and input. Wine/Winlator/Box64/FEX are optional compatibility components only; they are not the VM.

## Backend stack

### QEMU ARM64 reference path
- ARM64 virt machine with GIC v3 and high memory.
- KVM when the host exposes a usable KVM device.
- TCG multi-threaded fallback.
- UEFI pflash + per-VM NVRAM.
- Raw and qemu-img managed disk formats.
- VirtIO block/network/GPU/input/RNG.
- XHCI USB controller and UTC RTC.
- NAT + port forwarding or root/TAP.
- VirtIO sound using the Android AAudio backend when present.
- Software, VirGL and Gfxstream/rutabaga GPU modes.
- QMP lifecycle control.
- Local VNC/RFB display baseline.

### x86 full-system path
The command path selects a real qemu-system-x86_64 or qemu-system-i386 runtime and uses q35 + TCG/MTTCG. No user-mode emulation or application compatibility layer is substituted.

See docs/X86_FULL_SYSTEM_RUNTIME.md and scripts/build-qemu-android-x86-runtime.sh for the reproducible runtime provisioning path.
For a reproducible Android ARM64 QEMU source build (kept separate from the pinned default runtime), see docs/ARM64_QEMU_RUNTIME_BUILD.md and scripts/build-qemu-android-arm64-runtime.sh.

### Hardware hypervisor path
The orchestrator scores KVM, Gunyah, GenieZone, Halla and AVF based on host capability. AVF is explicitly capability-gated; a detected API surface does not mean a third-party app has permission to create an arbitrary production pVM.

## Shared files

When a verified virtiofsd exists, PhoenixVM starts it for the selected QEMU VM (QEMU/KVM or QEMU/TCG) and adds vhost-user-fs-pci using shared guest memory. If virtiofsd cannot start, PhoenixVM falls back to QEMU's 9p VirtFS path rather than pretending that VirtIO-FS is active.

The QEMU sidecar decision is based on backend capability, not the accelerator label: QEMU/KVM and QEMU/TCG both support the QEMU-managed virtiofsd and swtpm helpers. The current crosvm Android path does not expose the verified swtpm integration and rejects VMs that request TPM 2.0 so the orchestrator can continue to a QEMU fallback.

## TPM

When TPM is explicitly enabled, PhoenixVM requires a verified swtpm runtime, starts it before QEMU, waits for its Unix control socket, and wires an architecture-correct QEMU TPM device. If swtpm is absent or fails, the VM start fails rather than silently providing no TPM.

## Runtime integrity

The shipped DroidVM-Prebuilts runtime is pinned to commit c4998e150a08f0e60d141591fd63ea0010eb4f75. Runtime extraction rejects unsafe tar paths and verifies critical SHA-256 values.

## Verification boundary

GitHub CI can validate source, unit tests, packaging and build artifacts. Actual KVM/Gunyah/GenieZone/Halla/AVF access, GPU driver behavior, AAudio, Windows/Android guest boot and sustained thermal performance require representative physical Android devices.
