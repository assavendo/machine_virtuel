package com.assavendo.machinevirtuel.security

data class ThirdPartyComponent(
    val name: String,
    val role: String,
    val license: String,
    val source: String,
    val bundled: Boolean,
)

object ThirdPartyLicenseManifest {
    fun all(): List<ThirdPartyComponent> = listOf(
        ThirdPartyComponent("QEMU", "full-system VMM", "GPL-2.0-or-later", "https://www.qemu.org/", true),
        ThirdPartyComponent("crosvm", "hardware VM backend", "BSD-3-Clause", "https://chromium.googlesource.com/crosvm/crosvm/", true),
        ThirdPartyComponent("EDK2", "UEFI firmware", "BSD-2-Clause", "https://github.com/tianocore/edk2", true),
        ThirdPartyComponent("OVMF (Debian package)", "optional x86_64 UEFI firmware", "BSD-2-Clause-Patent and upstream component notices; see Debian copyright", "https://packages.debian.org/trixie/ovmf", false),
        ThirdPartyComponent("Bouncy Castle bcprov-jdk18on 1.87", "Ed25519 package signature verification", "Bouncy Castle License (MIT X Consortium based)", "https://github.com/bcgit/bc-java", true),
        ThirdPartyComponent("VirtIO", "guest paravirtual devices", "BSD/GPL components", "https://www.oasis-open.org/committees/virtio/", false),
        ThirdPartyComponent("Mesa/VirGL", "OpenGL virtualization", "MIT-style / GPL components", "https://mesa3d.org/", true),
        ThirdPartyComponent("gfxstream/rutabaga", "GPU virtualization", "Apache-2.0", "https://chromium.googlesource.com/graphics", true),
        ThirdPartyComponent("swtpm", "TPM emulator", "BSD-3-Clause / GPL components", "https://github.com/stefanberger/swtpm", false),
        ThirdPartyComponent("virtiofsd", "VirtIO-FS daemon", "Apache-2.0", "https://gitlab.com/virtio-fs/qemu/virtiofsd", false),
        ThirdPartyComponent("FEX", "optional guest compatibility", "MIT", "https://github.com/FEX-Emu/FEX", false),
        ThirdPartyComponent("Box64/Box86", "optional compatibility", "MIT", "https://github.com/ptitSeb/box64", false),
    )
}
