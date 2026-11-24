# Full-system backend integration

PhoenixVM uses an actual system VM runtime. The guest owns CPU state, RAM, firmware, virtual storage, networking, graphics and input. Application compatibility layers are not the VM.

## Runtime

The repository pins DroidVM-Prebuilts commit c4998e150a08f0e60d141591fd63ea0010eb4f75.

First launch streams the ARM64 tar.xz runtime through XZ, extracts it with traversal protection, validates critical executable and UEFI hashes against the upstream manifest, installs it privately and records a revision marker.

## QEMU backend

The reference path is QEMU System AArch64 with:
- ARM64 virt machine, GIC v3 and high memory;
- UEFI pflash plus writable per-VM NVRAM;
- persistent raw/qcow2/vhdx/vdi/vmdk disk formats through qemu-img;
- optional installer ISO;
- virtio-blk, virtio-net, virtio-gpu, virtio-tablet, virtio-keyboard and virtio-rng;
- QEMU XHCI controller;
- UTC RTC;
- optional 9p shared directory;
- local-only VNC framebuffer;
- QMP socket;
- serial and diagnostic logs.

The CPU backend is selected per host:
- KVM + host CPU when /dev/kvm is usable;
- otherwise TCG with multi-threaded translation.

## crosvm backend

When Android hardware virtualization is exposed, the orchestrator selects crosvm with KVM, Gunyah, GenieZone or Halla.

The current crosvm path is a genuine VMM path with UEFI, memory, vCPUs, block storage and backend selection. Its Android-native display/input and privileged network bridge are deliberately separate follow-up integrations because their host permissions and device-specific interfaces differ.

## Privileges and execution

Android sandboxing may block execution from app data and access to virtualization device nodes. RootProcessLauncher uses a normal app process when possible and a verified su executable when the user has root access. No acceleration is reported unless the selected backend is actually used.

## Display

QEMU VNC is exposed only on localhost and is consumed by the embedded RFB client. Raw framebuffer encoding plus keyboard and pointer events provide the compatibility baseline. The high-performance future path is direct virtio-gpu plus Vulkan/gfxstream shared buffers.

## Lifecycle

The runtime service marks STARTING before launch and RUNNING only after the real process survives startup. Process exit persists OFF/ERROR. QMP is used for orderly shutdown and for pause/resume on QEMU.

## Boundaries still remaining

Audio, TPM, live RAM snapshots, virtio-fs, root TAP bridges, direct Vulkan display, crosvm display/input, x86 persistent DynaRec cache, tiered JIT and performance validation remain separate work items rather than being falsely declared complete.
