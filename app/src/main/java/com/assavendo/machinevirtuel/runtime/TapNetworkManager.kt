package com.assavendo.machinevirtuel.runtime

import java.io.File

class TapNetworkManager {
    fun exists(name: String): Boolean =
        if (name.isBlank()) false else run("ip", "link", "show", "dev", name).first

    fun ensureInterface(name: String): Boolean {
        if (name.isBlank()) return false
        if (exists(name)) return true
        val created = run("ip", "tuntap", "add", "dev", name, "mode", "tap").first
        if (!created) return false
        run("ip", "link", "set", "dev", name, "up")
        return exists(name)
    }

    fun removeInterface(name: String) {
        if (name.isBlank()) return
        run("ip", "link", "delete", "dev", name)
    }

    private fun run(vararg command: String): Pair<Boolean, String> {
        return runCatching {
            val process = RootProcessLauncher.start(
                command = command.toList(),
                workingDir = File("/"),
                environment = emptyMap(),
                preferRoot = true,
            )
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            (code == 0) to (output + error)
        }.getOrElse { false to (it.message ?: "") }
    }
}
