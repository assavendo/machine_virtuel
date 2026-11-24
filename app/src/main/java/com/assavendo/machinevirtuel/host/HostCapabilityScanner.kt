package com.assavendo.machinevirtuel.host
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
class HostCapabilityScanner(private val context:Context){
 fun scan():HostCapabilities{
  val pm=context.packageManager; val runtime=Runtime.getRuntime()
  return HostCapabilities(
   supportedAbis=Build.SUPPORTED_ABIS.toList(),
   cpuCount=runtime.availableProcessors(),
   maxMemoryMb=runtime.maxMemory()/(1024*1024),
   vulkan=pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL),
   kvm=File("/dev/kvm").canRead(),gunyah=File("/dev/gunyah").exists(),
   genieZone=File("/dev/gzvm").exists(),halla=File("/dev/halla").exists(),
   avfHint=Build.VERSION.SDK_INT>=31,
   rootedHint=listOf("/system/bin/su","/system/xbin/su","/sbin/su").any{File(it).exists()}
  )
 }
}
