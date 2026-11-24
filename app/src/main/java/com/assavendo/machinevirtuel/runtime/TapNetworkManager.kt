package com.assavendo.machinevirtuel.runtime

import java.io.File

class TapNetworkManager {
    fun exists(name: String): Boolean =
        if (!validName(name)) false else run("ip", "link", "show", "dev", name).first

    fun ensureInterface(name: String): Boolean {
        if (!validName(name)) return false
        if (exists(name)) return true
        val created = run("ip", "tuntap", "add", "dev", name, "mode", "tap").first
        if (!created) return false
        run("ip", "link", "set", "dev", name, "up")
        return exists(name)
    }

    fun removeInterface(name: String) {
        if (!validName(name)) return
        run("ip", "link", "delete", "dev", name)
    }

    private fun validName(name: String): Boolean =
        name.matches(Regex("[A-Za-z0-9_.-]{1,15}"))

    private fun run(vararg command: String): Pair<Boolean, String> {
        return runCatching {
            val result = ProcessCommandRunner.run(
                command = command.toList(),
                workingDir = File("/"),
                environment = emptyMap(),
                preferRoot = true,
                timeoutMs = 10_000L,
                maxOutputChars = 64_000,
            )
            (result.exitCode == 0) to (result.stdout + result.stderr)
        }.getOrElse { false to (it.message ?: "TAP command failed.") }
    }
}
