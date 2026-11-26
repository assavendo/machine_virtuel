package com.assavendo.machinevirtuel.vm

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal, non-executing disk-container signature check for import-time validation.
 * It intentionally does not parse untrusted image metadata; QEMU remains responsible
 * for full image validation when the guest is actually launched.
 */
object DiskImageFormatDetector {
    fun detect(file: File): DiskFormat? {
        if (!file.isFile || file.length() <= 0L) return null
        return RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(8)
            val count = input.read(header)
            if (count >= 4 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x51, 0x46, 0x49, 0xfb.toByte()))) {
                return@use DiskFormat.QCOW2
            }
            if (count >= 8 && String(header, Charsets.US_ASCII) == "vhdxfile") {
                return@use DiskFormat.VHDX
            }
            if (count >= 4 && String(header, 0, 4, Charsets.US_ASCII) == "KDMV") {
                return@use DiskFormat.VMDK
            }
            if (file.length() >= 68L) {
                input.seek(64L)
                val vdi = ByteArray(4)
                if (input.read(vdi) == 4 &&
                    vdi.contentEquals(byteArrayOf(0x7f, 0x10, 0xda.toByte(), 0xbe.toByte()))
                ) {
                    return@use DiskFormat.VDI
                }
            }
            null
        }
    }

    fun validate(file: File, expected: DiskFormat): String? {
        val detected = detect(file)
        return when {
            detected != null && detected != expected ->
                "Le disque importé est au format ${detected.name}, mais ${expected.name} a été sélectionné."
            detected == null && expected != DiskFormat.RAW ->
                "Le format ${expected.name} ne peut pas être confirmé par l'en-tête du fichier. " +
                    "Sélectionne RAW ou importe une image ${expected.name} complète."
            else -> null
        }
    }
}
