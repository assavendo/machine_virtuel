# PhoenixVM — x86/x86_64 full-system runtime

PhoenixVM keeps x86/x86_64 as a true full-system virtual machine path. A real qemu-system-x86_64 is required; CPU user-mode emulation, Wine, Winlator, Box64, Box86 or FEX are not substitutes.

The current Termux QEMU system-x86-64 recipe builds x86_64-softmmu and i386-softmmu with Android/Termux support, VNC, VirtFS, VirGL and SLIRP. It pins QEMU 11.1.3 and SHA-256 5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22.

Run scripts/build-qemu-android-x86-runtime.sh in an Android/Termux aarch64 build environment or equivalent reproducible CI. The script verifies the source checksum and outputs runtime-pack/usr/bin/qemu-system-x86_64.

If the optional x86 runtime is absent, PhoenixVM must report an unavailable-runtime state and explain provisioning. It must never silently substitute Wine, Winlator or user-mode emulation.

QEMU TCG remains responsible for actual translation. PhoenixVM's cache, scheduler and thermal layers are policy/telemetry around QEMU's real translator. Near-native and +20 percent claims remain device-, version- and benchmark-specific until measured on physical hardware.
