package com.assavendo.machinevirtuel.runtime

import android.content.Context
import java.io.File

object FilePaths {
    private lateinit var root: File
    fun init(context: Context) { root = File(context.filesDir, "vm-runtime").apply { mkdirs() } }
    fun vmDir(vmId: String): File = File(root, vmId).apply { mkdirs() }
    fun controlSocket(vmId: String): File = File(vmDir(vmId), "crosvm.sock")
    fun qmp(vmId: String): File = File(vmDir(vmId), "qmp.sock")
    fun guestAgent(vmId: String): File = File(vmDir(vmId), "guest-agent.sock")
    fun serial(vmId: String): File = File(vmDir(vmId), "serial.log")
    fun log(vmId: String): File = File(vmDir(vmId), "qemu.log")
}
