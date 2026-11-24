package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.vm.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkPolicyTest {
    @Test fun macIsDeterministic() {
        val a = NetworkPolicy.deterministicMac("vm-one")
        val b = NetworkPolicy.deterministicMac("vm-one")
        assertEquals(a, b)
        assertTrue(Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$").matches(a))
    }

    @Test fun invalidMtuIsRejected() {
        val issues = NetworkPolicy(NetworkMode.NAT, "52:54:00:00:00:01", mtu = 100).validate()
        assertTrue(issues.any { it.contains("MTU") })
    }
}
