# Full-system backend integration

PhoenixVM is a real system virtual machine platform. The guest owns CPU state, RAM, firmware, virtual storage, network, graphics and input. Wine, Winlator, Box64 and FEX are not the VM.

## Runtime provenance

The project pins DroidVM-Prebuilts at commit c4998e150a08f0e60d141591fd63ea0010eb4f75.

At first VM start:
1. prebuilt-arm64-v8a.tar.xz is streamed through XZ;
2. a safe USTAR reader rejects absolute paths and traversal;
3. critical executables and firmware are checked against the upstream JSON SHA-256 manifest;
4. the runtime is installed into app-private storage;
5. a revision marker prevents repeated extraction.

## QEMU reference path

The display-capable full-system reference backend is QEMU System AArch64:
- ARM64 virt machine;
- GIC v3 and high-memory machine configuration;
- UEFI pflash code image plus per-VM writable NVRAM;
- persistent raw virtual disk;
- optional installer ISO;
- virtio-blk;
- virtio-net with user-mode NAT;
- virtio-gpu;
- virtio-tablet and virtio-keyboard;
- loopback VNC framebuffer;
- QMP Unix control socket;
- serial log and QEMU diagnostic log.

CPU:
- /dev/kvm selected when accessible;
- otherwise QEMU TCG with multi-threaded translation;
- no fake acceleration flag.

## crosvm hardware path

When supported Android hypervisor interfaces are exposed, the orchestrator can select crosvm with:
- KVM;
- Gunyah;
- GenieZone;
- Halla.

The command is a real VMM invocation with UEFI, guest memory, vCPUs and virtual block storage.

## Lifecycle

VM state is driven by the actual backend process:
- STARTING before launch;
- RUNNING only after the process is alive;
- OFF on clean exit;
- ERROR on failed/non-zero exit;
- QMP system_powerdown before forced QEMU termination.

## Remaining source-spec work

The following are not yet claimed complete:
- crosvm Android display/input bridge;
- root/TAP bridge networking;
- virtio-snd audio;
- virtio-fs/9p sharing;
- USB;
- TPM;
- pause/resume/suspend;
- qcow2/vhdx/vdi/vmdk management;
- snapshots/clones/import/export;
- persistent x86 translation cache integrated with a real DynaRec;
- tiered JIT;
- Vulkan shared-buffer display;
- Windows ARM64/x86 acceptance;
- Android guest acceptance;
- thermal-aware scheduler;
- sustained benchmark proof.
