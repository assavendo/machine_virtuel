package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.performance.HostMetrics
import com.assavendo.machinevirtuel.performance.PerformanceOptimizer
import com.assavendo.machinevirtuel.vm.PerformanceProfile
import com.assavendo.machinevirtuel.vm.VmConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class PerformanceOptimizerTest {
    private fun metrics(memoryMb:Long, throttling:Boolean)=HostMetrics(
        timestampMs=0L,
        processCpuPercent=20.0,
        systemCpuPercent=40.0,
        processRssMb=512L,
        availableMemoryMb=memoryMb,
        thermalStatus=0,
        skinTemperatureC=null,
        isThrottling=throttling,
    )

    @Test fun thermalPolicyReducesResources(){
        val vm=VmConfig(name="stable",vCpu=4,ramMb=4096,profile=PerformanceProfile.PERFORMANCE)
        val result=PerformanceOptimizer().decide(vm,metrics(2048,true))
        assertTrue(result.vCpu < vm.vCpu)
        assertTrue(result.ramMb < vm.ramMb)
    }

    @Test fun extremePolicyKeepsRequestedResourcesWhenHealthy(){
        val vm=VmConfig(name="extreme",vCpu=2,ramMb=2048,profile=PerformanceProfile.EXTREME)
        val result=PerformanceOptimizer().decide(vm,metrics(4096,false))
        assertTrue(result.vCpu == vm.vCpu)
        assertTrue(result.ramMb == vm.ramMb)
    }
}
