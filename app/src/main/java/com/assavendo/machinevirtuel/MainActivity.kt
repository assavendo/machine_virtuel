package com.assavendo.machinevirtuel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import com.assavendo.machinevirtuel.runtime.VmRuntimeService
import com.assavendo.machinevirtuel.ui.VmDisplayView
import com.assavendo.machinevirtuel.vm.VmManager
import com.assavendo.machinevirtuel.vm.VmState
import java.io.File

class MainActivity : Activity() {
    private lateinit var manager: VmManager
    private lateinit var vmContainer: LinearLayout
    private lateinit var capabilityLabel: TextView
    private lateinit var cacheLabel: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var isoTargetVm: String? = null

    private val bg = Color.rgb(11, 14, 19)
    private val panel = Color.rgb(21, 26, 34)
    private val textColor = Color.rgb(244, 247, 251)
    private val muted = Color.rgb(154, 165, 181)
    private val accent = Color.rgb(114, 167, 255)
    private val violet = Color.rgb(155, 123, 255)
    private val good = Color.rgb(86, 211, 100)
    private val bad = Color.rgb(255, 123, 114)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = VmManager(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 28)
        }
        scroll.addView(content)

        content.addView(title("PhoenixVM", 30f))
        content.addView(subtitle("Full-system VM Android • QEMU + crosvm • UEFI • VirtIO • cache persistant"))

        content.addView(sectionTitle("Hôte"))
        capabilityLabel = cardText()
        content.addView(capabilityLabel)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(actionButton("Analyser", accent) { refresh() })
        actions.addView(space(10))
        actions.addView(actionButton("Nouvelle VM", violet) { createVm() })
        content.addView(actions)

        content.addView(sectionTitle("Machines virtuelles"))
        vmContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(vmContainer)

        content.addView(sectionTitle("Cache de traduction"))
        cacheLabel = cardText()
        content.addView(cacheLabel)

        content.addView(TextView(this).apply {
            text = "Architecture : vraie VM complète. Le cœur ne repose pas sur Wine/Winlator. Le runtime QEMU/crosvm est extrait, vérifié par SHA-256 et exécuté comme backend réel."
            setTextColor(muted)
            textSize = 12f
            setPadding(4, 22, 4, 0)
        })

        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        refresh()
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun refresh() {
        val caps = manager.capabilities()
        capabilityLabel.text = buildString {
            append("ABI : ").append(caps.supportedAbis.joinToString()).append('\n')
            append("CPU logiques : ").append(caps.cpuCount).append('\n')
            append("RAM max processus : ").append(caps.maxMemoryMb).append(" MB\n")
            append("Vulkan : ").append(yesNo(caps.vulkan))
                .append("   KVM : ").append(yesNo(caps.kvm))
                .append("   Gunyah : ").append(yesNo(caps.gunyah)).append('\n')
            append("GenieZone : ").append(yesNo(caps.genieZone))
                .append("   Halla : ").append(yesNo(caps.halla))
                .append("   AVF possible : ").append(yesNo(caps.avfHint))
        }
        val stats = manager.cacheStats()
        cacheLabel.text = "Fichiers : " + stats.files + " • Taille : " + (stats.bytes / 1024) + " KiB"
        renderVms()
    }

    private fun renderVms() {
        vmContainer.removeAllViews()
        val vms = manager.list()
        if (vms.isEmpty()) {
            vmContainer.addView(cardText().apply {
                text = "Aucune VM. Crée une VM puis sélectionne une ISO Linux/Windows/Android lorsque nécessaire."
            })
            return
        }

        vms.forEach { vm ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 18, 18, 18)
                background = rounded(panel)
            }
            card.addView(title(vm.name, 19f))
            card.addView(subtitle(
                vm.guestOs + " • " + vm.arch.label + " • " + vm.ramMb + " MB • " +
                    vm.vCpu + " vCPU • backend " + vm.backend.label
            ))
            card.addView(subtitle(
                "État : " + vm.state.name +
                    " • disque : " + File(vm.diskPath).name +
                    " • ISO : " + if (vm.isoPath.isBlank()) "aucune" else File(vm.isoPath).name
            ))

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            when (vm.state) {
                VmState.RUNNING, VmState.STARTING -> {
                    row.addView(actionButton("Arrêter", bad) {
                        VmRuntimeService.stop(this@MainActivity, vm.id)
                    })
                    if (vm.state == VmState.RUNNING) {
                        row.addView(space(8))
                        row.addView(actionButton("Écran", accent) { showDisplay(vm.id) })
                    }
                }
                else -> {
                    row.addView(actionButton("Démarrer", good) {
                        manager.markStarting(vm.id)
                        VmRuntimeService.start(this@MainActivity, vm.id)
                    })
                }
            }
            row.addView(space(8))
            row.addView(actionButton("ISO", violet) { chooseIso(vm.id) })
            row.addView(space(8))
            row.addView(actionButton("Supprimer", Color.rgb(95, 104, 118)) {
                VmRuntimeService.stop(this@MainActivity, vm.id)
                manager.delete(vm.id)
                refresh()
            })
            card.addView(row)
            vmContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, 12)
            })
        }
    }

    private fun createVm() {
        val input = EditText(this).apply {
            hint = "Nom de la VM"
            setTextColor(textColor)
            setHintTextColor(muted)
        }
        AlertDialog.Builder(this)
            .setTitle("Nouvelle machine virtuelle")
            .setMessage("Création d'un PC virtuel complet avec disque 16 GiB sparse, UEFI/NVRAM persistants et backend auto.")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Créer") { _, _ ->
                manager.createDefault(input.text.toString().ifBlank { "Linux ARM64 VM" })
                refresh()
            }
            .show()
    }

    private fun chooseIso(vmId: String) {
        isoTargetVm = vmId
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/octet-stream"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            701,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 701 || resultCode != RESULT_OK) return
        val vmId = isoTargetVm ?: return
        val uri = data?.data ?: return
        val target = File(filesDir, "vms/" + vmId + "/installer.iso").apply {
            parentFile?.mkdirs()
        }
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        } ?: return
        manager.setIso(vmId, target.absolutePath)
        refresh()
        Toast.makeText(this, "ISO importée et stockée localement.", Toast.LENGTH_LONG).show()
    }

    private fun showDisplay(vmId: String) {
        val view = VmDisplayView(this)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Écran VM")
            .setView(view)
            .setPositiveButton("Fermer", null)
            .create()
        dialog.setOnDismissListener { view.disconnect() }
        dialog.setOnShowListener {
            view.postDelayed({
                view.connect(VmRuntimeService.vncPort(vmId))
            }, 1200)
        }
        dialog.show()
    }

    private fun yesNo(value: Boolean) = if (value) "oui" else "non"
    private fun title(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(textColor)
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, 8)
    }
    private fun subtitle(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(muted)
        setPadding(0, 0, 0, 8)
    }
    private fun cardText() = TextView(this).apply {
        textSize = 13f
        setTextColor(textColor)
        setPadding(18, 18, 18, 18)
        background = rounded(panel)
    }
    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(textColor)
        setTypeface(null, Typeface.BOLD)
        setPadding(2, 18, 2, 10)
    }
    private fun actionButton(label: String, color: Int, action: () -> Unit) =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            setOnClickListener { action() }
        }
    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = 24f
    }
    private fun space(width: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
