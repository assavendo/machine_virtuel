package com.assavendo.machinevirtuel
import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.vm.*
import kotlin.test.Test
import kotlin.test.assertEquals
class BackendSelectorTest{
 private fun caps(kvm:Boolean,gunyah:Boolean=false,gz:Boolean=false,halla:Boolean=false)=HostCapabilities(
  supportedAbis=listOf("arm64-v8a"),cpuCount=8,maxMemoryMb=4096,vulkan=true,
  kvm=kvm,gunyah=gunyah,genieZone=gz,halla=halla,avfHint=false,rootedHint=false)
 @Test fun arm64PrefersKvm(){assertEquals(BackendKind.KVM,BackendSelector.select(GuestArch.ARM64,caps(true)))}
 @Test fun x86FallsBackToQemu(){assertEquals(BackendKind.QEMU_TCG,BackendSelector.select(GuestArch.X86_64,caps(true)))}
 @Test fun arm64UsesGunyah(){assertEquals(BackendKind.GUNYAH,BackendSelector.select(GuestArch.ARM64,caps(false,gunyah=true)))}
}
