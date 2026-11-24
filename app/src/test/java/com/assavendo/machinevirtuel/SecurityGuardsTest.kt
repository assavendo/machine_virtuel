package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.security.SecurityGuards
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SecurityGuardsTest {
    @Test fun canonicalChildRejectsTraversal() {
        val root = createTempDir(prefix = "vm-root-")
        try {
            assertTrue(SecurityGuards.canonicalChild(root, File(root, "guest/disk.raw")).path.startsWith(root.canonicalPath))
            assertFails { SecurityGuards.canonicalChild(root, File(root, "../outside")) }
        } finally {
            root.deleteRecursively()
        }
    }
}
