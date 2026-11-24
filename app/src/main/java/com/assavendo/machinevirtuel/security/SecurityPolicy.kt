package com.assavendo.machinevirtuel.security

import java.io.File

data class SecurityPolicy(
    val allowRootBackend: Boolean = false,
    val allowHostPathSharing: Boolean = false,
    val allowTapNetwork: Boolean = false,
    val verifyImportedPackages: Boolean = true,
    val requireSha256ForImages: Boolean = true,
    val requireSignatureWhenPresent: Boolean = false,
    val isolateNetworkPerVm: Boolean = true,
)

object SecurityGuards {
    fun canonicalChild(parent: File, child: File): File {
        val p = parent.canonicalFile
        val c = child.canonicalFile
        require(c.path == p.path || c.path.startsWith(p.path + File.separator)) { "Accès hors du répertoire autorisé." }
        return c
    }

    fun validateUserPath(file: File, allowedRoots: List<File>): File {
        val canonical = file.canonicalFile
        require(allowedRoots.any {
            val r = it.canonicalFile
            canonical.path == r.path || canonical.path.startsWith(r.path + File.separator)
        }) { "Chemin utilisateur non autorisé par la politique de sécurité." }
        return canonical
    }
}
