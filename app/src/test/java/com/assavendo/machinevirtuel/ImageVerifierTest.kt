package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.ImageVerifier
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ImageVerifierTest {
    @Test fun sha256VerificationDetectsChanges() {
        val file = File.createTempFile("image-", ".bin")
        try {
            file.writeText("phoenixvm")
            val hash = ImageVerifier.sha256(file)
            assertTrue(ImageVerifier.verify(file, hash))
            file.appendText("changed")
            assertFalse(ImageVerifier.verify(file, hash))
        } finally {
            file.delete()
        }
    }
}
