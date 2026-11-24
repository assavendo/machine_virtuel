package com.assavendo.machinevirtuel.host
data class HostCapabilities(
 val supportedAbis:List<String>,val cpuCount:Int,val maxMemoryMb:Long,val vulkan:Boolean,
 val kvm:Boolean,val gunyah:Boolean,val genieZone:Boolean,val halla:Boolean,val avfHint:Boolean,val rootedHint:Boolean
)
