package com.assavendo.machinevirtuel.migration

import android.content.Context
import com.assavendo.machinevirtuel.vm.*
import org.json.JSONObject
import java.io.File

object VmMigrationManager {
    private const val CURRENT_SCHEMA = 3

    fun migrate(context: Context): Int {
        val prefs = context.getSharedPreferences("phoenixvm_vms", Context.MODE_PRIVATE)
        val version = prefs.getInt("schemaVersion", 1)
        if (version >= CURRENT_SCHEMA) return version
        val source = prefs.getString("vms", "[]") ?: "[]"
        val normalized = org.json.JSONArray(source).let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (!item.has("mode")) item.put("mode", VmMode.AUTO.name)
                if (!item.has("performancePreset")) item.put("performancePreset", PerformancePreset.BALANCED.name)
                if (!item.has("profile")) item.put("profile", PerformanceProfile.AUTO.name)
                if (!item.has("gpuBackend")) item.put("gpuBackend", GpuBackend.AUTO.name)
                if (!item.has("networkMode")) item.put("networkMode", NetworkMode.NAT.name)
            }
            array
        }
        prefs.edit().putString("vms", normalized.toString()).putInt("schemaVersion", CURRENT_SCHEMA).apply()
        return CURRENT_SCHEMA
    }

    fun backup(context: Context): File {
        val source = context.getSharedPreferences("phoenixvm_vms", Context.MODE_PRIVATE)
        val output = File(context.filesDir, "backups/vm-config-backup-" + System.currentTimeMillis() + ".json")
        output.parentFile?.mkdirs()
        output.writeText(
            JSONObject().put("schema", source.getInt("schemaVersion", CURRENT_SCHEMA))
                .put("vms", source.getString("vms", "[]"))
                .toString(2)
        )
        return output
    }
}
