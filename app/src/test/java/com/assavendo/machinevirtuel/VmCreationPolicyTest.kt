package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.GuestOsChoice
import com.assavendo.machinevirtuel.vm.VmCreationPolicy
import com.assavendo.machinevirtuel.vm.VmCreationWizardStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VmCreationPolicyTest {
    @Test
    fun wizardExposesSevenStableStepsAndRoutesValidationBackToTheOwningPage() {
        assertEquals(7, VmCreationWizardStep.entries.size)
        assertEquals(VmCreationWizardStep.ARCHITECTURE, VmCreationWizardStep.forValidationField("name"))
        assertEquals(VmCreationWizardStep.ARCHITECTURE, VmCreationWizardStep.forValidationField("arch"))
        assertEquals(VmCreationWizardStep.GUEST_OS_AND_MEDIA, VmCreationWizardStep.forValidationField("isoPath"))
        assertEquals(VmCreationWizardStep.GUEST_OS_AND_MEDIA, VmCreationWizardStep.forValidationField("androidArtifacts"))
        assertEquals(VmCreationWizardStep.GUEST_OS_AND_MEDIA, VmCreationWizardStep.forValidationField("androidAdbHostPort"))
        assertEquals(VmCreationWizardStep.RESOURCES, VmCreationWizardStep.forValidationField("ramMb"))
        assertEquals(VmCreationWizardStep.GRAPHICS, VmCreationWizardStep.forValidationField("gpuBackend"))
        assertEquals(VmCreationWizardStep.NETWORK, VmCreationWizardStep.forValidationField("tapName"))
        assertEquals(VmCreationWizardStep.SHARING, VmCreationWizardStep.forValidationField("sharedDirs"))
        assertEquals(VmCreationWizardStep.OPTIMIZATION_AND_SECURITY, VmCreationWizardStep.forValidationField("secureBoot"))
        assertTrue(VmCreationWizardStep.entries.all { it.title.isNotBlank() })
    }

    @Test
    fun guestChoicesExposeOnlySupportedArchitectures() {
        assertEquals(setOf(GuestArch.ARM64), GuestOsChoice.ANDROID.allowedArchitectures())
        assertEquals(setOf(GuestArch.X86_32), GuestOsChoice.DOS_LEGACY.allowedArchitectures())
        assertTrue(GuestArch.X86_64 in GuestOsChoice.WINDOWS.allowedArchitectures())
        assertFailsWith<IllegalArgumentException> {
            GuestOsChoice.ANDROID.guestOs(GuestArch.X86_64)
        }
    }

    @Test
    fun guestChoiceProducesExplicitPersistedOsIdentity() {
        assertEquals("Linux ARM64", GuestOsChoice.LINUX.guestOs(GuestArch.ARM64))
        assertEquals("Windows x86_64", GuestOsChoice.WINDOWS.guestOs(GuestArch.X86_64))
        assertEquals("DOS / legacy x86", GuestOsChoice.DOS_LEGACY.guestOs(GuestArch.X86_32))
    }

    @Test
    fun parsesTcpUdpAndShorthandPortForwards() {
        val forwards = VmCreationPolicy.parsePortForwards(
            "TCP:2222:22,udp:5353:5353\n8080:80"
        )
        assertEquals(3, forwards.size)
        assertEquals("tcp", forwards[0].protocol)
        assertEquals(2222, forwards[0].hostPort)
        assertEquals("udp", forwards[1].protocol)
        assertEquals("tcp", forwards[2].protocol)
    }

    @Test
    fun rejectsMalformedOutOfRangeAndDuplicatePorts() {
        assertFailsWith<IllegalArgumentException> {
            VmCreationPolicy.parsePortForwards("tcp:0:22")
        }
        assertFailsWith<IllegalArgumentException> {
            VmCreationPolicy.parsePortForwards("tcp:2222")
        }
        assertFailsWith<IllegalArgumentException> {
            VmCreationPolicy.parsePortForwards("tcp:2222:22,udp:2222:53")
        }
    }

    @Test
    fun sharedDirectoriesRequireAccessibleUniqueDirectoriesAndSafeTags() {
        val root = createTempDir(prefix = "phoenixvm-shares-")
        try {
            val one = java.io.File(root, "A host folder").apply { mkdir() }
            val result = VmCreationPolicy.sharedDirectories(listOf(one.absolutePath))
            assertEquals(1, result.size)
            assertEquals("A_host_folder", result.single().tag)
            assertEquals(one.canonicalPath, result.single().hostPath)
            assertFailsWith<IllegalArgumentException> {
                VmCreationPolicy.sharedDirectories(listOf(one.absolutePath, one.absolutePath))
            }
            assertFailsWith<IllegalArgumentException> {
                VmCreationPolicy.sharedDirectories(listOf(java.io.File(root, "missing").absolutePath))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
