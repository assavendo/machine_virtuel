package com.assavendo.machinevirtuel.runtime

import android.content.Context
import com.assavendo.machinevirtuel.vm.GuestArch
import java.io.File

class RuntimePaths(
    val root: File,
    val optionalRoot: File = File(root, "optional"),
) {
    constructor(context: Context) : this(
        File(context.filesDir, "runtime"),
        File(context.filesDir, "runtime-addons"),
    )

    val qemu = File(root, "usr/bin/qemu-system-aarch64")
    val qemuX86 = File(optionalRoot, "usr/bin/qemu-system-x86_64")
    val qemuX86Headless = File(optionalRoot, "usr/bin/qemu-system-x86_64-headless")
    val qemuI386 = File(optionalRoot, "usr/bin/qemu-system-i386")
    val qemuImg = File(root, "usr/bin/qemu-img")
    val crosvm = File(root, "usr/bin/crosvm")
    val libDir = File(root, "usr/lib")
    val x86LibDir = File(optionalRoot, "usr/lib")
    val toolsRoot = File(optionalRoot, "tools")
    val virtiofsdRoot = File(toolsRoot, "virtiofsd")
    val swtpmRoot = File(toolsRoot, "swtpm")
    val virtiofsd = File(virtiofsdRoot, "usr/libexec/virtiofsd")
    val swtpm = File(swtpmRoot, "usr/bin/swtpm")
    val virtiofsdLibDir = File(virtiofsdRoot, "usr/lib")
    val swtpmLibDir = File(swtpmRoot, "usr/lib")
    val qemuShare = File(root, "usr/share/qemu")
    val qemuShareX86 = File(optionalRoot, "usr/share/qemu")
    val edk2Qemu = File(root, "usr/share/droidvm/edk2-qemu.fd")
    val edk2QemuVars = File(root, "usr/share/droidvm/edk2-qemu.vars.fd")
    val edk2Gunyah = File(root, "usr/share/droidvm/edk2-gunyah.fd")
    val edk2GunyahVars = File(root, "usr/share/droidvm/edk2-gunyah.vars.fd")
    val edk2X86Code = File(optionalRoot, "usr/share/droidvm/edk2-x86_64-code.fd")
    val edk2X86Vars = File(optionalRoot, "usr/share/droidvm/edk2-x86_64-vars.fd")
    val edk2X86SecureCode = File(optionalRoot, "usr/share/droidvm/edk2-x86_64-secure-code.fd")
    val edk2X86SecureVars = File(optionalRoot, "usr/share/droidvm/edk2-x86_64-secure-vars.fd")

    fun isComplete(): Boolean =
        qemu.canExecute() && crosvm.canExecute() && qemuImg.canExecute() &&
            libDir.isDirectory && edk2Qemu.isFile && edk2QemuVars.isFile

    fun hasX86SystemQemu(): Boolean =
        qemuX86.canExecute() || qemuX86Headless.canExecute() || qemuI386.canExecute()

    fun hasX86Uefi(): Boolean = edk2X86Code.isFile && edk2X86Vars.isFile
    fun hasX86SecureBootUefi(): Boolean =
        edk2X86SecureCode.isFile && edk2X86SecureVars.isFile

    fun libraryDir(arch: GuestArch): File =
        if (arch == GuestArch.ARM64) libDir else x86LibDir

    fun dataDir(arch: GuestArch): File =
        if (arch == GuestArch.ARM64) qemuShare else qemuShareX86

    fun hasVirtiofsd(): Boolean = virtiofsd.canExecute()
    fun hasSwtpm(): Boolean = swtpm.canExecute()
    fun marker(): File = File(root, ".runtime-version")
}
