package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.vm.DiskFormat
import com.assavendo.machinevirtuel.vm.DiskImageFormatDetector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiskImageFormatDetectorTest {
    @Test
    fun detectsQcow2VhdxVdiAndVmdkHeaders() {
        val root = createTempDir(prefix = "phoenix-disk-header-")
        try {
            val qcow = File(root, "disk.qcow2").apply {
                writeBytes(byteArrayOf(0x51, 0x46, 0x49, 0xfb.toByte(), 0, 0, 0, 0))
            }
            val vhdx = File(root, "disk.vhdx").apply { writeBytes("vhdxfile".toByteArray(Charsets.US_ASCII)) }
            val vmdk = File(root, "disk.vmdk").apply { writeBytes("KDMV".toByteArray(Charsets.US_ASCII)) }
            val vdi = File(root, "disk.vdi").apply {
                writeBytes(ByteArray(68).also {
                    it[64] = 0x7f
                    it[65] = 0x10
                    it[66] = 0xda.toByte()
                    it[67] = 0xbe.toByte()
                })
            }
            assertEquals(DiskFormat.QCOW2, DiskImageFormatDetector.detect(qcow))
            assertEquals(DiskFormat.VHDX, DiskImageFormatDetector.detect(vhdx))
            assertEquals(DiskFormat.VMDK, DiskImageFormatDetector.detect(vmdk))
            assertEquals(DiskFormat.VDI, DiskImageFormatDetector.detect(vdi))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rawFilesRemainUnclassifiedAndMismatchedFormatsAreRejected() {
        val root = createTempDir(prefix = "phoenix-disk-header-")
        try {
            val raw = File(root, "disk.raw").apply { writeBytes(ByteArray(512) { 0x35 }) }
            val qcow = File(root, "disk.qcow2").apply {
                writeBytes(byteArrayOf(0x51, 0x46, 0x49, 0xfb.toByte(), 0, 0, 0, 0))
            }
            assertNull(DiskImageFormatDetector.detect(raw))
            assertNull(DiskImageFormatDetector.validate(raw, DiskFormat.RAW))
            assertNull(DiskImageFormatDetector.validate(qcow, DiskFormat.QCOW2))
            assertEquals(
                true,
                DiskImageFormatDetector.validate(qcow, DiskFormat.RAW)?.contains("QCOW2") == true,
            )
            assertEquals(
                true,
                DiskImageFormatDetector.validate(raw, DiskFormat.VMDK)?.contains("confirmé") == true,
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
