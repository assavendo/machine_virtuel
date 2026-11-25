package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.AudioRuntimePolicy
import com.assavendo.machinevirtuel.vm.VmConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioRuntimePolicyTest {
    @Test
    fun buildsExplicitAaudioTimingAndBufferSettings() {
        val vm = VmConfig(
            name = "audio",
            audioEnabled = true,
            audioFrequencyHz = 48_000,
            audioChannels = 2,
            audioBufferMs = 20,
            audioMuted = false,
        )
        val spec = AudioRuntimePolicy.from(vm).qemuAudioDeviceSpec()
        assertTrue(spec.contains("aaudio,id=snd0"))
        assertTrue(spec.contains("timer-period=20000"))
        assertTrue(spec.contains("out.frequency=48000"))
        assertTrue(spec.contains("out.channels=2"))
        assertTrue(spec.contains("out.buffer-length=20000"))
    }

    @Test
    fun muteUsesTheExplicitNullAudioBackend() {
        val vm = VmConfig(name = "muted", audioEnabled = true, audioMuted = true)
        assertTrue(AudioRuntimePolicy.from(vm).qemuAudioDeviceSpec() == "none,id=snd0")
    }

    @Test
    fun invalidBufferIsRejected() {
        val vm = VmConfig(name = "invalid", audioBufferMs = 1)
        assertFailsWith<IllegalArgumentException> { AudioRuntimePolicy.from(vm) }
    }
}
