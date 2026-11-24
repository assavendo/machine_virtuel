package com.assavendo.machinevirtuel.runtime

import android.content.Context
import java.io.File

class RuntimePaths(val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "runtime"))

    val qemu = File(root, "usr/bin/qemu-system-aarch64")
    val qemuX86 = File(root, "usr/bin/qemu-system-x86_64")
    val qemuX86Headless = File(root, "usr/bin/qemu-system-x86_64-headless")
    val qemuImg = File(root, "usr/bin/qemu-img")
    val crosvm = File(root, "usr/bin/crosvm")
    val virtiofsd = File(root, "usr/libexec/virtiofsd")
    val swtpm = File(root, "usr/bin/swtpm")
    val libDir = File(root, "usr/lib")
    val qemuShare = File(root, "usr/share/qemu")
    val edk2Qemu = File(root, "usr/share/droidvm/edk2-qemu.fd")
    val edk2QemuVars = File(root, "usr/share/droidvm/edk2-qemu.vars.fd")
    val edk2Gunyah = File(root, "usr/share/droidvm/edk2-gunyah.fd")
    val edk2GunyahVars = File(root, "usr/share/droidvm/edk2-gunyah.vars.fd")

    fun isComplete(): Boolean =
        qemu.canExecute() && crosvm.canExecute() && qemuImg.canExecute() &&
            libDir.isDirectory && edk2Qemu.isFile && edk2QemuVars.isFile

    fun hasX86SystemQemu(): Boolean = qemuX86.canExecute() || qemuX86Headless.canExecute()
    fun hasVirtiofsd(): Boolean = virtiofsd.canExecute()
    fun hasSwtpm(): Boolean = swtpm.canExecute()
    fun marker(): File = File(root, ".runtime-version")
}
