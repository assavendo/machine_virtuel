package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.guest.GuestCompatibilityCatalog
import com.assavendo.machinevirtuel.vm.GuestArch
import kotlin.test.Test
import kotlin.test.assertTrue

class GuestCompatibilityTest {
    @Test fun linuxArm64IsPrimaryTarget() {
        val entry = GuestCompatibilityCatalog.entries.first { it.id == "linux-arm64" }
        assertTrue(entry.fullSystem)
        assertTrue(entry.architecture == GuestArch.ARM64)
    }

    @Test fun x86CatalogContainsFullSystemTargets() {
        assertTrue(GuestCompatibilityCatalog.forArch(GuestArch.X86_64).isNotEmpty())
    }
}
