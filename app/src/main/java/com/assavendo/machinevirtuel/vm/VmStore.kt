package com.assavendo.machinevirtuel.vm
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
class VmStore(context:Context){
 private val prefs=context.getSharedPreferences("phoenixvm_vms",Context.MODE_PRIVATE)
 fun list():MutableList<VmConfig>{
  val result=mutableListOf<VmConfig>(); val array=JSONArray(prefs.getString("vms","[]")?:"[]")
  for(i in 0 until array.length()){
   val o=array.getJSONObject(i)
   result+=VmConfig(
    id=o.getString("id"),name=o.getString("name"),description=o.optString("description"),
    guestOs=o.optString("guestOs","Linux ARM64"),
    arch=GuestArch.valueOf(o.optString("arch",GuestArch.ARM64.name)),
    ramMb=o.optInt("ramMb",2048),vCpu=o.optInt("vCpu",2),diskPath=o.optString("diskPath"),
    firmwarePath=o.optString("firmwarePath"),
    backend=BackendKind.valueOf(o.optString("backend",BackendKind.QEMU_TCG.name)),
    profile=PerformanceProfile.valueOf(o.optString("profile",PerformanceProfile.AUTO.name)),
    state=VmState.valueOf(o.optString("state",VmState.OFF.name)),
    createdAt=o.optLong("createdAt",System.currentTimeMillis()),
    lastStartedAt=if(o.has("lastStartedAt")) o.optLong("lastStartedAt") else null
   )
  }
  return result
 }
 fun save(items:List<VmConfig>){
  val array=JSONArray()
  items.forEach{vm->array.put(JSONObject().apply{
   put("id",vm.id);put("name",vm.name);put("description",vm.description);put("guestOs",vm.guestOs)
   put("arch",vm.arch.name);put("ramMb",vm.ramMb);put("vCpu",vm.vCpu);put("diskPath",vm.diskPath)
   put("firmwarePath",vm.firmwarePath);put("backend",vm.backend.name);put("profile",vm.profile.name)
   put("state",vm.state.name);put("createdAt",vm.createdAt);vm.lastStartedAt?.let{put("lastStartedAt",it)}
  })}
  prefs.edit().putString("vms",array.toString()).apply()
 }
}
