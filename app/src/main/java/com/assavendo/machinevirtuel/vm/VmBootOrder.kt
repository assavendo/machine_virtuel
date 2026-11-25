package com.assavendo.machinevirtuel.vm

/** Canonical boot-device order shared by VM validation and the QEMU command builder. */
object VmBootOrder {
    private val aliases = mapOf(
        "disk" to "disk",
        "cdrom" to "cdrom",
        "iso" to "cdrom",
        "network" to "network",
        "net" to "network",
    )

    fun canonical(order: List<String>): List<String> =
        order.map { aliases[it.trim().lowercase()] ?: it.trim().lowercase() }

    fun validate(order: List<String>): String? {
        if (order.isEmpty()) return "L'ordre de démarrage doit contenir au moins un périphérique."
        if (order.size > aliases.values.distinct().size) {
            return "L'ordre de démarrage ne peut contenir plus de trois types de périphériques."
        }
        val canonical = order.map { entry ->
            val value = aliases[entry.trim().lowercase()]
                ?: return "Périphérique de démarrage inconnu : $entry."
            value
        }
        if (canonical.distinct().size != canonical.size) {
            return "Un périphérique ne peut apparaître qu'une seule fois dans l'ordre de démarrage."
        }
        return null
    }

    /** The lowest index is the first device considered by guest firmware. */
    fun priorities(order: List<String>): Map<String, Int> {
        validate(order)?.let { throw IllegalArgumentException(it) }
        return canonical(order).withIndex().associate { it.value to it.index }
    }
}
