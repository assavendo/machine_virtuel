package com.assavendo.machinevirtuel.runtime

import android.app.PendingIntent
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.File
import java.util.UUID
import com.assavendo.machinevirtuel.vm.UsbPassthroughConfig

data class UsbHostDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val bus: Int?,
    val address: Int?,
    val hasPermission: Boolean,
    val directDeviceAccess: Boolean,
    val isHub: Boolean,
)

data class UsbHostAttachment(
    val vendorId: Int,
    val productId: Int,
    val bus: Int,
    val address: Int,
    val guestReset: Boolean,
    val requiresRoot: Boolean,
)

object UsbPassthroughManager {
    private const val MAX_DEVICES_PER_VM = 8

    fun list(context: Context): List<UsbHostDeviceInfo> {
        val manager = context.getSystemService(UsbManager::class.java)
            ?: return emptyList()

        return manager.deviceList.values
            .map { device -> inspect(manager, device) }
            .sortedWith(compareBy<UsbHostDeviceInfo> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceName })
    }

    fun requestPermission(
        context: Context,
        device: UsbDevice,
        onResult: (Boolean) -> Unit,
    ) {
        val manager = context.getSystemService(UsbManager::class.java)
            ?: return onResult(false)

        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }

        val action = context.packageName + ".USB_PERMISSION." + UUID.randomUUID()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != action) return
                runCatching { receiverContext.unregisterReceiver(this) }
                onResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(action))
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        runCatching {
            manager.requestPermission(device, pendingIntent)
        }.onFailure {
            runCatching { context.unregisterReceiver(receiver) }
            onResult(false)
        }
    }

    fun findDevice(context: Context, deviceName: String): UsbDevice? =
        context.getSystemService(UsbManager::class.java)?.deviceList?.get(deviceName)

    fun resolveAll(
        context: Context,
        configs: List<UsbPassthroughConfig>,
    ): List<UsbHostAttachment> {
        require(configs.size <= MAX_DEVICES_PER_VM) {
            "Une VM ne peut pas attacher plus de " + MAX_DEVICES_PER_VM + " périphériques USB hôte."
        }

        val manager = context.getSystemService(UsbManager::class.java)
            ?: error("UsbManager Android indisponible.")

        val devices = manager.deviceList.values.map { inspect(manager, it) }
        val used = mutableSetOf<String>()

        return configs.map { config ->
            require(config.vendorId in 0..0xffff && config.productId in 0..0xffff) {
                "Identifiant USB vendor/product invalide."
            }
            require(config.serialNumber.length <= 256) {
                "Le numéro de série USB dépasse 256 caractères."
            }

            val matches = devices.filter {
                !it.isHub &&
                    it.vendorId == config.vendorId &&
                    it.productId == config.productId &&
                    (config.serialNumber.isBlank() || it.serialNumber == config.serialNumber)
            }

            require(matches.isNotEmpty()) {
                "Périphérique USB " + hex(config.vendorId) + ":" + hex(config.productId) + " introuvable."
            }
            require(matches.size == 1) {
                "Plusieurs périphériques correspondent à " + hex(config.vendorId) + ":" + hex(config.productId) +
                    "; renseignez le numéro de série."
            }

            val device = matches.single()
            require(device.hasPermission) {
                "Permission Android USB manquante pour " + device.deviceName + ". Autorisez d'abord explicitement ce périphérique."
            }
            require(device.bus != null && device.address != null) {
                "Impossible de déterminer bus/adresse USB pour " + device.deviceName + "."
            }
            val requiresRoot = !device.directDeviceAccess
            require(device.directDeviceAccess || RootProcessLauncher.hasRoot()) {
                "Accès direct au nœud USB " + device.deviceName +
                    " indisponible et aucun runtime root utilisable n'est détecté."
            }
            require(used.add(device.deviceName)) {
                "Le même périphérique USB est déjà attaché à cette VM."
            }

            UsbHostAttachment(
                vendorId = device.vendorId,
                productId = device.productId,
                bus = device.bus,
                address = device.address,
                guestReset = config.guestReset,
                requiresRoot = requiresRoot,
            )
        }
    }

    private fun inspect(manager: UsbManager, device: UsbDevice): UsbHostDeviceInfo {
        val path = device.deviceName
        val match = Regex("^/dev/bus/usb/(\\d{3})/(\\d{3})$").matchEntire(path)
        val bus = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val address = match?.groupValues?.getOrNull(2)?.toIntOrNull()

        val serial = runCatching { device.serialNumber }.getOrNull()
        val isHub = device.deviceClass == UsbConstants.USB_CLASS_HUB ||
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HUB
            }

        return UsbHostDeviceInfo(
            deviceName = path,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
            productName = runCatching { device.productName }.getOrNull(),
            serialNumber = serial,
            bus = bus,
            address = address,
            hasPermission = runCatching { manager.hasPermission(device) }.getOrDefault(false),
            directDeviceAccess = File(path).let { it.canRead() && it.canWrite() },
            isHub = isHub,
        )
    }

    private fun hex(value: Int): String = "0x%04x".format(value)
}
