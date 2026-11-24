# Implementation status against the source specification

The repository is deliberately honest: a feature is COMPLETE only after implementation and validation, not after a stub exists.

## Full-system core

| Area | Status |
|---|---|
| Real full-system boundary | Implemented |
| Runtime provenance and pinned prebuilt | Implemented |
| Safe runtime extraction | Implemented |
| Runtime SHA-256 verification | Implemented for critical files |
| QEMU ARM64 system backend | Implemented |
| QEMU TCG MTTCG path | Implemented |
| QEMU KVM selection | Implemented, device validation pending |
| UEFI + per-VM NVRAM | Implemented |
| Persistent raw disk | Implemented |
| ISO attachment | Implemented |
| VirtIO block | Implemented in QEMU command path |
| VirtIO network | Implemented in QEMU command path with user NAT |
| VirtIO GPU | Implemented in QEMU command path |
| VirtIO keyboard/tablet | Implemented in QEMU command path |
| QMP shutdown | Implemented |
| Embedded VNC/RFB display | Implemented baseline |
| crosvm backend selection | Implemented |
| Gunyah/GenieZone/Halla crosvm launch foundation | Implemented |
| Android/root execution fallback | Root launcher implemented |
| Runtime foreground lifecycle | Implemented |

## Still incomplete from the full specification

- crosvm native Android display/input bridge;
- root TAP/bridge networking;
- virtio-snd with AAudio/Oboe;
- virtio-fs/9p sharing;
- USB controller/device mapping;
- TPM;
- true pause/resume/suspend;
- qcow2/vhdx/vdi/vmdk image management;
- snapshots, clones, export/import;
- persistent x86 translation cache integrated with a DynaRec;
- tiered JIT implementation;
- direct Vulkan/gfxstream shared-buffer display path;
- Windows ARM64 and x86_64 acceptance;
- Android guest acceptance;
- thermal-aware scheduler and sustained performance control;
- complete benchmark suite proving the 20% target;
- device farm / on-device hardware matrix.

## MVP status

The core MVP path is now a genuine full-system QEMU VM path. It is not a Wine/Winlator launcher. The missing MVP-level acceptance items are real device boot validation and the remaining peripheral requirements from the source document.
