package com.assavendo.machinevirtuel.runtime

import android.content.Context
import com.assavendo.machinevirtuel.host.HostCapabilityScanner
import com.assavendo.machinevirtuel.vm.VmStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticExporter {
    fun export(context: Context, output: File): File {
        output.parentFile?.mkdirs()
        val tmp = File(output.parentFile, output.name + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered(128 * 1024)).use { zip ->
            putText(zip, "host/capabilities.txt", HostCapabilityScanner(context).scan().toString())
            putText(zip, "host/build.txt", "model=${android.os.Build.MODEL}\nmanufacturer=${android.os.Build.MANUFACTURER}\nandroid=${android.os.Build.VERSION.SDK_INT}\n")
            putText(zip, "vms/list.txt", VmStore(context).list().joinToString("\n") { it.toString() })
            RuntimeDiagnostics.run(context).forEach { d ->
                putText(zip, "runtime/${d.component.replace(' ', '_')}.txt", "ok=${d.ok}\ndetail=${d.detail}\n")
            }
            val logs = File(context.filesDir, "vms")
            logs.walkTopDown().filter { it.isFile && it.name.endsWith(".log") }.take(50).forEach { log ->
                val relative = logs.toPath().relativize(log.toPath()).toString()
                putFile(zip, "logs/$relative", log)
            }
        }
        check(tmp.renameTo(output) || runCatching { output.delete(); tmp.renameTo(output) }.getOrDefault(false)) {
            "Impossible de finaliser le rapport diagnostic."
        }
        return output
    }

    private fun putText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().buffered(128 * 1024).use { it.copyTo(zip, 128 * 1024) }
        zip.closeEntry()
    }
}
