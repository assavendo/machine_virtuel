package com.assavendo.machinevirtuel

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.assavendo.machinevirtuel.engine.NativeEngine
import com.assavendo.machinevirtuel.vm.VmManager
import com.assavendo.machinevirtuel.vm.VmState

class MainActivity : Activity() {
    private lateinit var manager: VmManager
    private lateinit var capabilityLabel: TextView
    private lateinit var cacheLabel: TextView
    private lateinit var vmContainer: LinearLayout

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
        content.addView(subtitle("Machine virtuelle complète Android • orchestrateur multi-backend"))

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

        content.addView(sectionTitle("Moteur natif"))
        content.addView(cardText().apply {
            text = "Core natif : " + NativeEngine.version()
        })

        content.addView(TextView(this).apply {
            text = "Aucune VM n'est annoncée comme active tant qu'un runtime QEMU/crosvm réellement exécutable n'est pas installé. Cette règle évite les faux positifs."
            setTextColor(muted)
            textSize = 12f
            setPadding(4, 22, 4, 0)
        })

        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        refresh()
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
                .append("   AVF possible : ").append(yesNo(caps.avfHint)).append('\n')
            append("Indice root : ").append(yesNo(caps.rootedHint))
        }
        val stats = manager.cacheStats()
        cacheLabel.text = "Fichiers persistants : " + stats.files + " • Taille : " + (stats.bytes / 1024) + " KiB"
        renderVms()
    }

    private fun renderVms() {
        vmContainer.removeAllViews()
        val vms = manager.list()
        if (vms.isEmpty()) {
            vmContainer.addView(cardText().apply {
                text = "Aucune VM. Crée un profil ARM64 pour commencer la validation de l'orchestrateur."
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
            card.addView(subtitle("État : " + vm.state.name + " • profil : " + vm.profile.name))

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (vm.state == VmState.RUNNING) {
                row.addView(actionButton("Arrêter", bad) {
                    manager.stop(vm.id)
                    refresh()
                })
            } else {
                row.addView(actionButton("Démarrer", good) {
                    when (val result = manager.start(vm.id)) {
                        is com.assavendo.machinevirtuel.vm.StartResult.Started ->
                            toast("Backend sélectionné : " + result.backend.label)
                        is com.assavendo.machinevirtuel.vm.StartResult.NotReady ->
                            toast(result.reason)
                    }
                    refresh()
                })
            }
            row.addView(space(8))
            row.addView(actionButton("Supprimer", Color.rgb(95, 104, 118)) {
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
            .setMessage("Création d'un profil ARM64 complet. Le disque, l'ISO et le runtime backend seront reliés aux vrais moteurs dans les prochaines étapes.")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Créer") { _, _ ->
                manager.createDefault(input.text.toString().ifBlank { "Linux ARM64 VM" })
                refresh()
            }
            .show()
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

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 24f
    }

    private fun space(width: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
