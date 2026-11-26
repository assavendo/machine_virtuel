# PhoenixVM — reproducible Android ARM64 QEMU runtime build

## Purpose

The pinned DroidVM-Prebuilts bundle remains the default ARM64 runtime. This recipe adds a reproducible source-build route for the full-system `qemu-system-aarch64` executable and `qemu-img`, using the same QEMU release and verified source archive as the optional x86 runtime builder.

It is a build/provenance prerequisite for future QEMU/TCG work. It does **not** currently replace the pinned runtime automatically, change the app's runtime selection, or implement persistent TCG Translation Blocks.

## Build host and dependencies

Run the script natively from an Android ARM64/aarch64 environment such as Termux. It intentionally rejects x86 hosts and does not claim to be an Android cross-compiler.

The build environment must provide:
- `curl`, `sha256sum`, `tar`, `ninja`, `pkg-config`, and `getconf`;
- GLib and Pixman development packages;
- libslirp development files exposed through the `slirp` pkg-config module;
- enough free storage and memory for a full QEMU build.

The script derives the project root from its own location, so it can be invoked from any working directory:

```bash
bash scripts/build-qemu-android-arm64-runtime.sh
```

## Source integrity and build configuration

- QEMU release: `11.1.3`.
- Source archive: `https://download.qemu.org/qemu-11.1.3.tar.xz`.
- Expected SHA-256: `5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22`.
- QEMU target: `aarch64-softmmu`.
- TCG is enabled; VNC, VirtFS and SLIRP are enabled.
- GUI, OpenGL/VirGL, SPICE, SDL, GTK, curses and QEMU's guest-agent binary are disabled for this runtime profile.

The checksum is verified before extraction. The package records SHA-256 for each installed file and for the final archive.

## Outputs

The build writes to:

- `.build/qemu-11.1.3-arm64/prefix/runtime-pack/usr/bin/qemu-system-aarch64`;
- `.build/qemu-11.1.3-arm64/prefix/runtime-pack/usr/bin/qemu-img`;
- `.build/qemu-11.1.3-arm64/phoenixvm-arm64-runtime.tar.xz`;
- `.build/qemu-11.1.3-arm64/phoenixvm-arm64-runtime.tar.xz.sha256`.

The package contains the installed QEMU prefix libraries and a `SHA256SUMS` manifest. The ARM64 UEFI firmware is intentionally not rebuilt or replaced by this script; PhoenixVM must continue using the separately verified ARM64 firmware from the pinned runtime bundle.

## Integration and validation boundary

This recipe makes the ARM64 QEMU source version, source hash, target list and build flags reproducible. The generated package is not yet consumed by the Android app's runtime installer and is not a tested drop-in replacement for the pinned DroidVM-Prebuilts binary. Dynamic dependency resolution, package import/rollback, device execution and guest boot must be implemented and validated before it can become a supported app runtime.

The build also does not add a persistent-TB patch. QEMU TCG's live Translation Blocks contain host-code and guest-state relationships, direct-jump chains and invalidation bookkeeping. A cache that reloads those blocks must be designed and tested inside the corresponding QEMU source tree; a generic Android-side artifact cache cannot safely serialize them.

Section 9 therefore remains **PARTIEL**. The reproducible ARM64 build path removes one prerequisite, but the actual translator consumption, safe TCG export/import/invalidation integration and measured reduction in repeated-launch translation cost remain open.
