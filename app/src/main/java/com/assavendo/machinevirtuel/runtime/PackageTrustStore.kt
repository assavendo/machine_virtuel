package com.assavendo.machinevirtuel.runtime

import android.content.Context

object PackageTrustStore {
    private const val PREFS = "phoenixvm_package_trust_v1"
    private const val KEYS = "trusted_sha256_keys"
    private val fingerprintRegex = Regex("[A-Fa-f0-9]{64}")

    fun normalize(fingerprint: String): String {
        require(fingerprintRegex.matches(fingerprint)) { "Empreinte Ed25519 invalide." }
        return fingerprint.lowercase()
    }

    fun isTrusted(context: Context, fingerprint: String): Boolean {
        val normalized = normalize(fingerprint)
        return prefs(context).getStringSet(KEYS, emptySet()).orEmpty().contains(normalized)
    }

    fun trust(context: Context, fingerprint: String) {
        val normalized = normalize(fingerprint)
        val current = prefs(context).getStringSet(KEYS, emptySet()).orEmpty().toMutableSet()
        current += normalized
        check(prefs(context).edit().putStringSet(KEYS, current).commit()) {
            "Impossible d'enregistrer la clé de confiance du package."
        }
    }

    fun revoke(context: Context, fingerprint: String) {
        val normalized = normalize(fingerprint)
        val current = prefs(context).getStringSet(KEYS, emptySet()).orEmpty().toMutableSet()
        current.remove(normalized)
        prefs(context).edit().putStringSet(KEYS, current).apply()
    }

    fun trusted(context: Context): Set<String> =
        prefs(context).getStringSet(KEYS, emptySet()).orEmpty().toSet().sorted().toSet()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
