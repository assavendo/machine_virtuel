package com.assavendo.machinevirtuel.security

data class UsbDeviceRule(
    val vendorId: Int,
    val productId: Int,
    val allow: Boolean,
    val description: String = "",
)

data class UsbPolicy(
    val hostPassthroughAllowed: Boolean = false,
    val rules: List<UsbDeviceRule> = emptyList(),
) {
    fun isAllowed(vendorId: Int, productId: Int): Boolean =
        hostPassthroughAllowed &&
            rules.firstOrNull { it.vendorId == vendorId && it.productId == productId }?.allow == true
}
