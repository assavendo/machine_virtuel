package com.assavendo.machinevirtuel.vm
import com.assavendo.machinevirtuel.host.HostCapabilities
object BackendSelector{
 fun select(arch:GuestArch,caps:HostCapabilities):BackendKind{
  if(arch==GuestArch.ARM64){
   when{
    caps.kvm->return BackendKind.KVM
    caps.gunyah->return BackendKind.GUNYAH
    caps.genieZone->return BackendKind.GENIEZONE
    caps.halla->return BackendKind.HALLA
    caps.avfHint->return BackendKind.AVF
   }
  }
  return BackendKind.QEMU_TCG
 }
}
