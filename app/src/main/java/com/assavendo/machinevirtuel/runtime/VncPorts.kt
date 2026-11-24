package com.assavendo.machinevirtuel.runtime
object VncPorts {
    fun portFor(vmId: String): Int {
        val h = vmId.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff }
        return 5901 + (h % 200)
    }
}
