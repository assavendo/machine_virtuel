package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmConfig

data class AudioRuntimePolicy(
    val frequencyHz: Int,
    val channels: Int,
    val bufferMs: Int,
    val timerPeriodUs: Long,
    val muted: Boolean,
) {
    fun qemuAudioDeviceSpec(): String {
        if (muted) return "none,id=snd0"
        val bufferUsec = bufferMs.toLong() * 1000L
        return buildString {
            append("aaudio,id=snd0")
            append(",timer-period=").append(timerPeriodUs)
            append(",out.frequency=").append(frequencyHz)
            append(",in.frequency=").append(frequencyHz)
            append(",out.channels=").append(channels)
            append(",in.channels=").append(channels)
            append(",out.fixed-settings=on")
            append(",in.fixed-settings=on")
            append(",out.format=s16")
            append(",in.format=s16")
            append(",out.buffer-length=").append(bufferUsec)
            append(",in.buffer-length=").append(bufferUsec)
        }
    }

    companion object {
        fun from(vm: VmConfig): AudioRuntimePolicy {
            require(vm.audioFrequencyHz in 8_000..192_000) {
                "audioFrequencyHz doit être compris entre 8000 et 192000."
            }
            require(vm.audioChannels in 1..8) {
                "audioChannels doit être compris entre 1 et 8."
            }
            require(vm.audioBufferMs in 2..500) {
                "audioBufferMs doit être compris entre 2 et 500 ms."
            }
            return AudioRuntimePolicy(
                frequencyHz = vm.audioFrequencyHz,
                channels = vm.audioChannels,
                bufferMs = vm.audioBufferMs,
                timerPeriodUs = vm.audioBufferMs.toLong() * 1000L,
                muted = vm.audioMuted,
            )
        }
    }
}
