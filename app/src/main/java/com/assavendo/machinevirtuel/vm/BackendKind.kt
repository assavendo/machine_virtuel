package com.assavendo.machinevirtuel.vm
enum class BackendKind(val label:String,val binaryName:String){
    KVM("KVM","crosvm"), GUNYAH("Gunyah","crosvm"), GENIEZONE("GenieZone","crosvm"),
    HALLA("Halla","crosvm"), AVF("AVF / pKVM","crosvm"), QEMU_TCG("QEMU TCG","qemu-system")
}
enum class GuestArch(val label:String){ ARM64("ARM64"), X86_64("x86_64") }
enum class VmState{ OFF,STARTING,RUNNING,PAUSED,SUSPENDED,STOPPING,ERROR,RECOVERY }
enum class PerformanceProfile{ AUTO,STABLE,PERFORMANCE,EXTREME }
