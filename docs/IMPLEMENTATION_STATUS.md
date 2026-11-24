# Implementation status

This status is deliberately honest and is checked against the 64-section source specification.

- Full VM architecture: designed.
- VM catalog and lifecycle model: implemented.
- Host capability detection: implemented.
- Backend abstraction and selection: implemented.
- Persistent translation-cache infrastructure: implemented, but it is not yet the actual translated CPU block cache until a real JIT backend is integrated.
- Rust native core: implemented and built by CI.
- Android UI: implemented.
- GitHub Actions APK pipeline: implemented.
- Real crosvm runtime: not embedded yet.
- Real QEMU system runtime: not embedded yet.
- VirtIO device backends: not embedded yet.
- UEFI boot pipeline: not embedded yet.
- Real GPU virtualization path: not embedded yet.
- Real network/audio/input device plumbing: not embedded yet.
- Windows 10/11 guest support: not complete.
- Snapshot/export/import: not complete.
- On-device performance proof (+20%): not measured yet.

The repository must not be described as having a complete VM until the missing runtime/device milestones are actually implemented and validated on hardware.
