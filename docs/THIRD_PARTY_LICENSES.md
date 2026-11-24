# Third-party components, provenance and distribution obligations

This inventory records the third-party component catalogue published by the pinned upstream DroidVM source and the binary hash manifest for the exact DroidVM-Prebuilts revision consumed by PhoenixVM. It is a provenance aid, not a legal opinion or proof that every shipped binary has been completely identified.

## Pinned upstream payload

- Repository: https://github.com/Droid-VM/DroidVM-Prebuilts
- Pinned Git commit: `c4998e150a08f0e60d141591fd63ea0010eb4f75`
- Archive: `prebuilt-arm64-v8a.tar.xz`
- Hash manifest: [`THIRD_PARTY/DroidVM-Prebuilts.json`](../THIRD_PARTY/DroidVM-Prebuilts.json)
- Upstream component catalogue: [`THIRD_PARTY/DroidVM-license.yaml`](../THIRD_PARTY/DroidVM-license.yaml)
- Attribution/provenance note: [`THIRD_PARTY/DroidVM-Prebuilts.txt`](../THIRD_PARTY/DroidVM-Prebuilts.txt)

PhoenixVM must preserve upstream notices and license texts. Pinning a Git revision and checking SHA-256 detects accidental or malicious payload changes relative to the pinned manifest; it does not itself satisfy source-code, notice, offer-of-source, or patent obligations.

## Components listed by the upstream catalogue

The following names and SPDX-like identifiers are transcribed from the upstream DroidVM `license.yaml`. They describe the upstream app's declared inventory; they are not a definitive file-by-file mapping of every ELF object in the prebuilt archive.

| Component | Upstream-declared license | Upstream source |
|---|---|---|
| DroidVM | GPL-3.0-or-later | https://github.com/Droid-VM/DroidVM |
| crosvm | BSD-3-Clause | https://github.com/Droid-VM/crosvm |
| QEMU | GPL-2.0 | https://gitlab.com/qemu-project/qemu |
| EDK2 | BSD-2-Clause-Patent | https://github.com/tianocore/edk2 |
| Linux | GPL-2.0 | https://kernel.org/ |
| libvncserver | GPL-2.0 | https://github.com/LibVnc/libvncserver |
| Magisk | GPL-3.0 | https://github.com/topjohnwu/Magisk |
| libsu | Apache-2.0 | https://github.com/topjohnwu/libsu |
| BusyBox | GPL-2.0 | https://busybox.net/ |
| Termux | GPL-3.0 | https://github.com/termux/termux-app |
| SnakeYAML | Apache-2.0 | https://bitbucket.org/snakeyaml/snakeyaml/ |
| pv | GPL-3.0-or-later | https://www.ivarch.com/programs/pv.shtml |
| XZ for Java | 0BSD | https://tukaani.org/xz/java.html |
| udisasm | MIT | https://gitlab.com/bztsrc/udisasm |
| jsoncpp | MIT | https://github.com/open-source-parsers/jsoncpp |
| gfxstream | Apache-2.0 | https://android.googlesource.com/platform/hardware/google/gfxstream/ |
| netbox | GPL-3.0 | https://github.com/Droid-VM/netbox |
| gvisor-vswitch | Apache-2.0 | https://github.com/Droid-VM/gvisor-vswitch |
| pseudo-bridge-rs | Apache-2.0 | https://github.com/KusakabeShi/pseudo-bridge-rs |
| linux-boot-extractor | GPL-3.0 | https://github.com/Droid-VM/linux-boot-extractor |
| bridgedhcp | GPL-3.0 | https://github.com/Droid-VM/bridgedhcp |

## Binary payload coverage

The pinned SHA-256 manifest enumerates the archive files by path and digest. It includes (among others) QEMU system/image binaries, crosvm, helper networking tools, graphics libraries, VNC/USB/SLIRP dependencies, EDK2 firmware, Android-specific kernel modules, a kernel/initramfs and utility binaries.

The current upstream catalogue does not separately identify every library that appears in the hash manifest. Examples that need an exact package/version/license-to-file mapping before any public redistribution include libepoxy, libffi, GLib components, libslirp, libusb, libpixman, PCRE2, virglrenderer, libvulkan_freedreno and other platform-provided objects. Do not infer their licenses from the names alone.

## Release gate — required before publishing or distributing builds

1. Generate a complete SPDX or CycloneDX SBOM for the exact runtime archive and every app dependency, including file path, component version, source URL, license expression, copyright notice and checksum.
2. Map every manifest entry to its exact upstream source/package version; document generated, patched and platform-provided objects separately.
3. Include the full corresponding license texts and copyright notices required by each dependency. For GPL components, verify the corresponding-source and installation-information obligations that apply to the way the binary is conveyed.
4. Preserve NOTICE files and patent/trademark notices, and provide reproducible source/build instructions for any locally built component.
5. Review Android SDK/NDK, Google components, OEM blobs and firmware redistribution terms separately.
6. Run a release-time license scanner and fail the release if any packaged component is unknown, unlicensed, incompatible with the intended distribution model, or missing a required notice.

The upstream inventory and pinned digest manifest are necessary tracking artifacts, but this document deliberately does not mark the final redistribution audit complete until the above file-level evidence has been collected and reviewed.
