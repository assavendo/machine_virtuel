package com.assavendo.machinevirtuel.vm

import java.io.File
import java.util.Locale

/** Stable page order and validation routing for the A–G VM creation assistant. */
enum class VmCreationWizardStep(val title: String) {
    ARCHITECTURE("Architecture"),
    GUEST_OS_AND_MEDIA("Système invité et médias"),
    RESOURCES("Ressources"),
    GRAPHICS("GPU et affichage"),
    NETWORK("Réseau"),
    SHARING("Partage hôte / invité"),
    OPTIMIZATION_AND_SECURITY("Optimisation et sécurité");

    companion object {
        fun forValidationField(field: String): VmCreationWizardStep = when (field) {
            "name", "arch", "backend" -> ARCHITECTURE
            "guestOs", "guestVersion", "isoPath", "diskPath", "kernelPath",
            "initrdPath", "dtbPath", "androidSystemPath", "androidVendorPath",
            "androidUserdataPath", "androidArtifacts", "androidAdbEnabled",
            "androidAdbHostPort", "guestAgentEnabled" -> GUEST_OS_AND_MEDIA
            "ramMb", "vCpu", "diskSizeGiB", "diskFormat" -> RESOURCES
            "gpuBackend", "displayWidth", "displayHeight", "headless" -> GRAPHICS
            "network", "networkMode", "tapName" -> NETWORK
            "sharedDirs", "sharedDirPath" -> SHARING
            else -> OPTIMIZATION_AND_SECURITY
        }
    }
}

enum class GuestOsChoice(val label: String, val defaultArchitecture: GuestArch) {
    LINUX("Linux", GuestArch.ARM64),
    WINDOWS("Windows", GuestArch.ARM64),
    ANDROID("Android guest", GuestArch.ARM64),
    BSD("BSD", GuestArch.ARM64),
    DOS_LEGACY("DOS / legacy", GuestArch.X86_32),
    CUSTOM_ISO("ISO personnalisée", GuestArch.ARM64),
    EXISTING_DISK("Disque existant", GuestArch.ARM64);

    fun allowedArchitectures(): Set<GuestArch> = when (this) {
        LINUX, CUSTOM_ISO, EXISTING_DISK -> GuestArch.entries.toSet()
        WINDOWS, BSD -> setOf(GuestArch.ARM64, GuestArch.X86_64)
        ANDROID -> setOf(GuestArch.ARM64)
        DOS_LEGACY -> setOf(GuestArch.X86_32)
    }

    fun guestOs(architecture: GuestArch): String {
        require(architecture in allowedArchitectures()) {
            "L'architecture ${architecture.label} n'est pas compatible avec ${label}."
        }
        return when (this) {
            LINUX -> "Linux ${architecture.label}"
            WINDOWS -> "Windows ${architecture.label}"
            ANDROID -> "Android ${architecture.label}"
            BSD -> "BSD ${architecture.label}"
            DOS_LEGACY -> "DOS / legacy x86"
            CUSTOM_ISO -> "Custom ISO ${architecture.label}"
            EXISTING_DISK -> "Existing disk ${architecture.label}"
        }
    }
}

/**
 * Pure input policy for the creation wizard. It performs no Android UI or storage I/O.
 */
object VmCreationPolicy {
    private val portToken = Regex("(?i)^(tcp|udp):(\\d{1,5}):(\\d{1,5})$")
    private val shortPortToken = Regex("^(\\d{1,5}):(\\d{1,5})$")

    /**
     * Accepts comma/newline-separated TCP:host:guest, UDP:host:guest or host:guest entries.
     * A host port is unique regardless of protocol to avoid ambiguous QEMU user-net rules.
     */
    fun parsePortForwards(raw: String): List<PortForward> {
        val entries = raw.split(',', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        require(entries.size <= 64) { "Au maximum 64 redirections réseau sont autorisées." }
        val result = entries.mapIndexed { index, entry ->
            val full = portToken.matchEntire(entry)
            val short = shortPortToken.matchEntire(entry)
            val protocol: String
            val hostPort: Int
            val guestPort: Int
            when {
                full != null -> {
                    protocol = full.groupValues[1].lowercase(Locale.ROOT)
                    hostPort = full.groupValues[2].toInt()
                    guestPort = full.groupValues[3].toInt()
                }
                short != null -> {
                    protocol = "tcp"
                    hostPort = short.groupValues[1].toInt()
                    guestPort = short.groupValues[2].toInt()
                }
                else -> throw IllegalArgumentException(
                    "Redirection #${index + 1} invalide. Format : TCP:2222:22 ou 2222:22."
                )
            }
            require(hostPort in 1..65535 && guestPort in 1..65535) {
                "Les ports de la redirection #${index + 1} doivent être compris entre 1 et 65535."
            }
            PortForward(protocol, hostPort, guestPort)
        }
        require(result.map { it.hostPort }.distinct().size == result.size) {
            "Deux redirections ne peuvent pas partager le même port hôte."
        }
        return result
    }

    /**
     * Validates writable host directories and assigns safe, stable VirtIO mount tags.
     * Directory selection is intentionally explicit; no broad Android storage permission is used.
     */
    fun sharedDirectories(paths: List<String>): List<SharedDirectory> {
        val nonBlank = paths.map { it.trim() }.filter { it.isNotEmpty() }
        require(nonBlank.size <= 16) { "Au maximum 16 dossiers partagés sont autorisés." }
        val result = mutableListOf<SharedDirectory>()
        val canonicalPaths = mutableSetOf<String>()
        val tags = mutableSetOf<String>()
        nonBlank.forEachIndexed { index, raw ->
            val directory = File(raw).canonicalFile
            require(directory.isDirectory && directory.canRead() && directory.canWrite()) {
                "Le dossier partagé #${index + 1} doit exister et être accessible en lecture/écriture."
            }
            require(canonicalPaths.add(directory.path)) {
                "Un même dossier partagé ne peut pas être déclaré plusieurs fois."
            }
            val baseTag = directory.name
                .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                .trim('_', '.', '-')
                .ifBlank { "shared${index + 1}" }
                .take(36)
            var tag = baseTag
            var suffix = 2
            while (!tags.add(tag)) {
                val tail = "_${suffix++}"
                tag = baseTag.take(36 - tail.length) + tail
            }
            result += SharedDirectory(directory.absolutePath, tag, readOnly = false)
        }
        return result
    }
}
