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
import com.assavendo.machinevirtuel.benchmark.BenchmarkCatalog
import com.assavendo.machinevirtuel.developer.DeveloperTools
import com.assavendo.machinevirtuel.device.DeviceProfileDatabase
import com.assavendo.machinevirtuel.guest.GuestCompatibilityCatalog
import com.assavendo.machinevirtuel.migration.VmMigrationManager
import com.assavendo.machinevirtuel.runtime.SnapshotManager
import com.assavendo.machinevirtuel.runtime.RuntimeInstaller
import com.assavendo.machinevirtuel.runtime.VmRuntimeService
import com.assavendo.machinevirtuel.runtime.RecoveryPlanStore
import com.assavendo.machinevirtuel.runtime.VmPackageManager
import com.assavendo.machinevirtuel.runtime.PackageTrustStore
import com.assavendo.machinevirtuel.security.ThirdPartyLicenseManifest
import com.assavendo.machinevirtuel.ui.VmDisplayView
import com.assavendo.machinevirtuel.vm.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var manager: VmManager
    private lateinit var vmContainer: LinearLayout
    private lateinit var capabilityLabel: TextView
    private lateinit var cacheLabel: TextView
    private lateinit var statusLabel: TextView
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
    private val teal = Color.rgb(70, 180, 170)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = VmManager(this)
        VmMigrationManager.migrate(this)

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
        content.addView(subtitle("Moteur de machine virtuelle full-system • QEMU/crosvm • UEFI • VirtIO • orchestration adaptative"))
        statusLabel = cardText()
        content.addView(statusLabel)

        content.addView(sectionTitle("Hôte"))
        capabilityLabel = cardText()
        content.addView(capabilityLabel)

        val hostActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hostActions.addView(actionButton("Analyser", accent) { refresh() })
        hostActions.addView(space(8))
        hostActions.addView(actionButton("Diagnostic", teal) { showRuntimeDiagnostics() })
        hostActions.addView(space(8))
        hostActions.addView(actionButton("Développeur", violet) { showDeveloperTools() })
        hostActions.addView(space(8))
        hostActions.addView(actionButton("Gestes", teal) { configureGestures() })
        content.addView(hostActions)

        val projectActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        projectActions.addView(actionButton("Nouvelle VM", violet) { createVm() })
        projectActions.addView(space(8))
        projectActions.addView(actionButton("Importer .vmxpack", accent) { importVmPackage() })
        projectActions.addView(space(8))
        projectActions.addView(actionButton("Runtime x86", teal) { chooseX86Runtime() })
        projectActions.addView(space(8))
        projectActions.addView(actionButton("Virtio-fs", teal) { chooseRuntimeAddon("virtiofsd", 704) })
        projectActions.addView(space(8))
        projectActions.addView(actionButton("TPM logiciel", teal) { chooseRuntimeAddon("swtpm", 705) })
        projectActions.addView(space(8))
        projectActions.addView(actionButton("Licence", teal) { showLicenses() })
        content.addView(projectActions)

        content.addView(sectionTitle("Machines virtuelles"))
        vmContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(vmContainer)

        content.addView(sectionTitle("Compatibilité invités"))
        content.addView(cardText().apply {
            text = GuestCompatibilityCatalog.entries.joinToString("\n") { guest ->
                "• " + guest.name + " — " + guest.architecture.label + " — " + guest.status.name +
                    if (guest.notes.isBlank()) "" else " — " + guest.notes
            }
        })

        content.addView(sectionTitle("Cache de traduction"))
        cacheLabel = cardText()
        content.addView(cacheLabel)

        content.addView(TextView(this).apply {
            text = "Règle : PhoenixVM exécute une vraie VM complète. Wine, Winlator, Box64, Box86 et FEX ne remplacent jamais le VMM."
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
                handler.postDelayed(this, 1500)
            }
        }, 1500)
    }

    private fun refresh() {
        val caps = manager.capabilities()
        val profile = DeviceProfileDatabase.match(caps.manufacturer, caps.model, caps.soc)
        capabilityLabel.text = buildString {
            append("Appareil : ").append(caps.manufacturer).append(' ').append(caps.model).append('\n')
            append("SoC : ").append(caps.soc).append(" • API ").append(caps.apiLevel).append('\n')
            append("Kernel : ").append(caps.kernelVersion.take(80)).append('\n')
            append("ABI : ").append(caps.supportedAbis.joinToString()).append('\n')
            append("CPU : ").append(caps.cpuCount).append(" • RAM totale : ").append(caps.totalMemoryMb).append(" MB • libre : ").append(caps.availableMemoryMb).append(" MB\n")
            append("Vulkan : ").append(yesNo(caps.vulkan))
                .append(" • OpenGL ES : ").append(yesNo(caps.openGlEs))
                .append(" • USB host : ").append(yesNo(caps.usbHost)).append('\n')
            append("KVM : ").append(yesNo(caps.kvm))
                .append(" • Gunyah : ").append(yesNo(caps.gunyah))
                .append(" • GenieZone : ").append(yesNo(caps.genieZone))
                .append(" • Halla : ").append(yesNo(caps.halla)).append('\n')
            append("AVF : ").append(yesNo(caps.avfAvailable || caps.avfHint))
                .append(" • Root hint : ").append(yesNo(caps.rootedHint))
                .append(" • SELinux enforcing : ").append(yesNo(caps.selinuxEnforcing)).append('\n')
            append("Profil appareil : ").append(profile.family.name)
                .append(" • validation : ").append(profile.validation.name)
        }
        val stats = manager.cacheStats()
        cacheLabel.text = "Fichiers : " + stats.files + " • Taille : " + (stats.bytes / 1024) + " KiB"
        statusLabel.text = "Runtime : " + if (manager.runtimeReady()) "prêt" else "non installé / incomplet"
        renderVms()
    }

    private fun renderVms() {
        vmContainer.removeAllViews()
        val vms = manager.list()
        if (vms.isEmpty()) {
            vmContainer.addView(cardText().apply {
                text = "Aucune VM. Crée une VM ou importe un package."
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
                vm.guestOs + " • " + vm.arch.label + " • " + vm.ramMb + " MB • " + vm.vCpu + " vCPU • " + vm.diskFormat.name
            ))
            val orchestration = runCatching { manager.selectBackend(vm.id) }.getOrNull()
            card.addView(subtitle(
                "État : " + vm.state.name + " • backend : " + vm.backend.label +
                    " • auto : " + (orchestration?.selected?.label ?: "—")
            ))
            card.addView(subtitle(
                "Mode " + vm.mode.label + " • profil " + vm.performancePreset.label +
                    " • GPU " + vm.gpuBackend.name + " • réseau " + vm.networkMode.name
            ))

            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            when (vm.state) {
                VmState.RUNNING -> {
                    row1.addView(actionButton("Pause", accent) { VmRuntimeService.pause(this@MainActivity, vm.id) })
                    row1.addView(space(6))
                    row1.addView(actionButton("Reboot", accent) { VmRuntimeService.reboot(this@MainActivity, vm.id) })
                    row1.addView(space(6))
                    row1.addView(actionButton("Écran", accent) { showDisplay(vm.id) })
                    row1.addView(space(6))
                    row1.addView(actionButton("Arrêter", bad) { VmRuntimeService.stop(this@MainActivity, vm.id) })
                }
                VmState.PAUSED -> {
                    row1.addView(actionButton("Reprendre", good) { VmRuntimeService.resume(this@MainActivity, vm.id) })
                    row1.addView(space(6))
                    row1.addView(actionButton("Arrêter", bad) { VmRuntimeService.stop(this@MainActivity, vm.id) })
                }
                VmState.STARTING, VmState.STOPPING -> {
                    row1.addView(actionButton("Force Stop", bad) { VmRuntimeService.forceStop(this@MainActivity, vm.id) })
                }
                VmState.ERROR -> {
                    row1.addView(actionButton("Récupération", teal) { showRecovery(vm) })
                    row1.addView(space(6))
                    row1.addView(actionButton("Démarrer", good) { VmRuntimeService.start(this@MainActivity, vm.id) })
                }
                else -> row1.addView(actionButton("Démarrer", good) { VmRuntimeService.start(this@MainActivity, vm.id) })
            }
            row1.addView(space(6))
            row1.addView(actionButton("ISO", violet) { chooseIso(vm.id) })
            card.addView(row1)

            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(actionButton("Snapshot", teal) {
                runCatching { manager.snapshot(vm.id, "manual") }
                    .onSuccess { toast("Snapshot créé.") }
                    .onFailure { toast("Snapshot : " + (it.message ?: "échec")) }
            })
            row2.addView(space(6))
            row2.addView(actionButton("Snapshots", teal) { showSnapshots(vm) })
            row2.addView(space(6))
            row2.addView(actionButton("Optimiser", accent) { showOptimization(vm) })
            row2.addView(space(6))
            row2.addView(actionButton("Cloner", violet) { cloneVm(vm) })
            card.addView(row2)

            val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row3.addView(actionButton("Exporter", accent) { exportVm(vm) })
            row3.addView(space(6))
            row3.addView(actionButton("Rapport", teal) { exportDeveloperReport(vm) })
            row3.addView(space(6))
            row3.addView(actionButton("Supprimer", Color.rgb(95, 104, 118)) {
                VmRuntimeService.forceStop(this@MainActivity, vm.id)
                manager.delete(vm.id)
                refresh()
            })
            card.addView(row3)

            vmContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, 12)
            })
        }
    }

    private fun createVm() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 0, 18, 0)
        }
        val name = EditText(this).apply {
            hint = "Nom de la VM"
            setTextColor(textColor)
            setHintTextColor(muted)
            setText("Linux ARM64 VM")
        }
        val arch = Spinner(this)
        arch.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, GuestArch.entries.map { it.label })
        val mode = Spinner(this)
        mode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, VmMode.entries.map { it.label })
        val preset = Spinner(this)
        preset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PerformancePreset.entries.map { it.label })
        layout.addView(TextView(this).apply { text = "Nom"; setTextColor(textColor) })
        layout.addView(name)
        layout.addView(TextView(this).apply { text = "Architecture"; setTextColor(textColor) })
        layout.addView(arch)
        layout.addView(TextView(this).apply { text = "Mode"; setTextColor(textColor) })
        layout.addView(mode)
        layout.addView(TextView(this).apply { text = "Profil"; setTextColor(textColor) })
        layout.addView(preset)

        AlertDialog.Builder(this)
            .setTitle("Création VM full-system")
            .setMessage("Initialise disque, UEFI/NVRAM, réseau, VirtIO et profil de performance. Les guests Windows/Android restent à valider sur appareil réel.")
            .setView(layout)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Créer") { _, _ ->
                val selectedArch = GuestArch.entries[arch.selectedItemPosition]
                val selectedMode = VmMode.entries[mode.selectedItemPosition]
                val selectedPreset = PerformancePreset.entries[preset.selectedItemPosition]
                runCatching {
                    val seed = VmConfig(
                        name = name.text.toString().ifBlank { "PhoenixVM" },
                        arch = selectedArch,
                        mode = selectedMode,
                        performancePreset = selectedPreset,
                        guestOs = when (selectedArch) {
                            GuestArch.ARM64 -> "Linux ARM64"
                            GuestArch.X86_64 -> "Linux x86_64"
                            GuestArch.X86_32 -> "DOS / legacy x86"
                        }
                    )
                    seed.backend = VmOrchestrator().choose(seed, manager.capabilities()).selected
                    manager.create(seed)
                }.onFailure { toast(it.message ?: "Création impossible.") }
                refresh()
            }
            .show()
    }

    private fun cloneVm(vm: VmConfig) {
        val input = EditText(this).apply {
            hint = "Nom du clone"
            setText(vm.name + " Clone")
        }
        AlertDialog.Builder(this).setTitle("Cloner VM").setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Cloner") { _, _ ->
                runCatching { manager.clone(vm.id, input.text.toString()) }
                    .onFailure { toast(it.message ?: "Clonage impossible.") }
                refresh()
            }.show()
    }

    private fun showSnapshots(vm: VmConfig) {
        val list = SnapshotManager.list(filesDir, vm.id)
        val body = if (list.isEmpty()) "Aucun snapshot." else list.joinToString("\n\n") {
            it.name + "\n" + it.createdAt + "\nprotégé=" + it.protected + "\nsha256=" + it.diskSha256
        }
        AlertDialog.Builder(this).setTitle("Snapshots — " + vm.name)
            .setMessage(body).setPositiveButton("Fermer", null).show()
    }

    private fun showOptimization(vm: VmConfig) {
        val advice = runCatching { manager.optimize(vm.id) }.getOrElse {
            toast(it.message ?: "Optimisation impossible.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Auto Optimizer — " + vm.name)
            .setMessage(
                "Backend : " + advice.currentBackend + "\n" +
                    "vCPU recommandé : " + advice.recommendedVcpus + "\n" +
                    "RAM recommandée : " + advice.recommendedRamMb + " MB\n" +
                    "GPU : " + advice.recommendedGpuMode + "\n" +
                    "Thermique : " + advice.thermalState + "\n\n" +
                    advice.actions.joinToString("\n")
            )
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun exportVm(vm: VmConfig) {
        val output = File(filesDir, "exports/" + vm.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".vmxpack")
        runCatching { manager.exportVm(vm.id, output, true) }
            .onSuccess { toast("Package exporté : " + it.absolutePath) }
            .onFailure { toast(it.message ?: "Export impossible.") }
    }

    private fun chooseX86Runtime() {
        AlertDialog.Builder(this)
            .setTitle("Installer le runtime x86")
            .setMessage(
                "Cette extension ajoute QEMU x86_64/i386 et le firmware OVMF au moteur. " +
                    "Elle exécute du code natif sur l'appareil. Choisissez uniquement une archive " +
                    "que vous avez construite avec scripts/build-qemu-android-x86-runtime.sh " +
                    "ou obtenue depuis une source de confiance. Les SHA-256 internes détectent " +
                    "la corruption, mais ne constituent pas une signature du distributeur."
            )
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Choisir l'archive") { _, _ ->
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "application/x-xz"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    },
                    703
                )
            }
            .setNeutralButton("Retirer l'extension") { _, _ ->
                runCatching { RuntimeInstaller.removeOptionalX86(this) }
                    .onSuccess { toast("Runtime x86 retiré.") }
                    .onFailure { toast(it.message ?: "Retrait impossible.") }
                refresh()
            }
            .show()
    }

    private fun chooseRuntimeAddon(toolId: String, requestCode: Int) {
        val label = if (toolId == "virtiofsd") "virtiofsd" else "swtpm"
        val script = if (toolId == "virtiofsd") {
            "scripts/build-virtiofsd-android-arm64-addon.sh"
        } else {
            "scripts/build-swtpm-android-arm64-addon.sh"
        }
        AlertDialog.Builder(this)
            .setTitle("Installer l'extension $label")
            .setMessage(
                "L'extension est optionnelle et exécutera du code natif sur Android. " +
                    "Choisissez uniquement une archive TAR.XZ que vous avez construite avec $script " +
                    "ou obtenue depuis une source de confiance. Le manifeste SHA-256 protège contre " +
                    "la corruption et les archives incomplètes; il ne remplace pas une signature de provenance."
            )
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Choisir l'archive") { _, _ ->
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "application/x-xz"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    },
                    requestCode
                )
            }
            .setNeutralButton("Retirer") { _, _ ->
                runCatching { RuntimeInstaller.removeOptionalTool(this, toolId) }
                    .onSuccess { toast("Extension $label retirée.") }
                    .onFailure { toast(it.message ?: "Retrait impossible.") }
                refresh()
            }
            .show()
    }

    private fun importVmPackage() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/octet-stream"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            702
        )
    }

    private fun importTrustedVmPackage(uri: android.net.Uri) {
        val local = File(cacheDir, "vm-import-" + System.currentTimeMillis() + ".vmxpack")
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                local.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            } ?: error("Impossible d'ouvrir le package sélectionné.")
        }.onFailure {
            local.delete()
            toast("Import package : " + (it.message ?: "échec"))
        }.onSuccess {
            val fingerprint = runCatching { VmPackageManager.packageKeyFingerprint(local) }.getOrNull()
            if (fingerprint == null) {
                local.delete()
                toast("Package signé invalide ou clé absente.")
                return@onSuccess
            }
            if (PackageTrustStore.isTrusted(this, fingerprint)) {
                runCatching { manager.importVm(local) }
                    .onSuccess { toast("Package importé.") }
                    .onFailure { toast("Import package : " + (it.message ?: "échec")) }
                local.delete()
                refresh()
                return@onSuccess
            }

            AlertDialog.Builder(this)
                .setTitle("Nouvelle clé de signature")
                .setMessage(
                    "Ce package est signé par une clé qui n'est pas encore approuvée sur cet appareil.\n\n" +
                        "Empreinte SHA-256 :\n" + fingerprint + "\n\n" +
                        "N'approuvez cette clé que si vous avez vérifié cette empreinte par un canal de confiance."
                )
                .setNegativeButton("Annuler") { _, _ -> local.delete() }
                .setPositiveButton("Approuver et importer") { _, _ ->
                    runCatching {
                        PackageTrustStore.trust(this, fingerprint)
                        manager.importVm(local)
                    }
                        .onSuccess { toast("Clé approuvée et package importé.") }
                        .onFailure { toast("Import package : " + (it.message ?: "échec")) }
                    local.delete()
                    refresh()
                }
.show()
        }
    }

    private fun showDeveloperTools() {
        val firstVm = manager.list().firstOrNull()
        val report = firstVm?.let { runCatching { DeveloperTools(this).report(it.id) }.getOrNull() }
        val cases = BenchmarkCatalog.cases.joinToString("\n") {
            "• " + it.name + " — " + it.durationHintMinutes + " min"
        }
        val body = "Device profiles : " + DeviceProfileDatabase.all().size +
            "\nBenchmarks définis : " + BenchmarkCatalog.cases.size +
            "\n\n" + cases +
            "\n\nRapport VM : " + (report?.timings ?: "aucune VM")
        AlertDialog.Builder(this).setTitle("Mode développeur").setMessage(body)
            .setPositiveButton("Exporter diagnostic") { _, _ ->
                runCatching { DeveloperTools(this).exportDiagnostics() }
                    .onSuccess { toast("Diagnostic exporté.") }
                    .onFailure { toast(it.message ?: "Échec diagnostic.") }
            }.setNeutralButton("Fermer", null).show()
    }

    private fun exportDeveloperReport(vm: VmConfig) {
        runCatching { DeveloperTools(this).exportReport(vm.id) }
            .onSuccess { toast("Rapport exporté.") }
            .onFailure { toast(it.message ?: "Rapport impossible.") }
    }

    private fun showLicenses() {
        val body = ThirdPartyLicenseManifest.all().joinToString("\n\n") {
            it.name + " — " + it.license + "\n" + it.role + "\n" + it.source + "\nBundlé=" + it.bundled
        }
        AlertDialog.Builder(this).setTitle("Composants tiers").setMessage(body)
            .setPositiveButton("Fermer", null).show()
    }

    private fun showRuntimeDiagnostics() {
        val diagnostics = runCatching { manager.runtimeDiagnostics() }
            .getOrElse {
                listOf(com.assavendo.machinevirtuel.runtime.RuntimeDiagnostic("Runtime", false, it.message ?: "error"))
            }
        val body = diagnostics.joinToString("\n") {
            (if (it.ok) "✓ " else "✗ ") + it.component + " — " + it.detail
        }
        AlertDialog.Builder(this).setTitle("Diagnostic du runtime").setMessage(body)
            .setPositiveButton("Fermer", null).show()
    }

    private fun showTrustedPackageKeys() {
        val keys = PackageTrustStore.trusted(this)
        if (keys.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Clés packages")
                .setMessage("Aucune clé de signature approuvée sur cet appareil.")
                .setPositiveButton("Fermer", null)
                .show()
            return
        }
        val labels = keys.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Clés packages approuvées")
            .setMultiChoiceItems(labels, null) { _, which, checked ->
                if (!checked) {
                    PackageTrustStore.revoke(this, labels[which])
                    toast("Clé révoquée.")
                }
            }
            .setPositiveButton("Fermer", null)
            .setMessage("Décochez une empreinte pour révoquer immédiatement son approbation.")
            .show()
    }

    private fun showRecovery(vm: VmConfig) {
        val plan = RecoveryPlanStore.read(filesDir, vm.id)
        if (plan == null) {
            toast("Aucun plan de récupération disponible pour cette VM.")
            return
        }
        val message = buildString {
            append("La VM a terminé en erreur. Aucune donnée ne sera supprimée automatiquement.\n\n")
            plan.actions.forEachIndexed { index, action ->
                append(index + 1).append(". ").append(action).append('\n')
            }
            plan.safeSnapshot?.let {
                append("\nSnapshot recommandé : ").append(it.name)
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Récupération — " + vm.name)
            .setMessage(message)
            .setNegativeButton("Fermer", null)
            .setPositiveButton("Retenter", null)

        if (plan.safeSnapshot?.isDirectory == true) {
            builder.setNeutralButton("Restaurer snapshot") { _, _ ->
                runCatching {
                    VmManager(this).restoreSnapshot(vm.id, plan.safeSnapshot)
                    RecoveryPlanStore.clear(filesDir, vm.id)
                }
                    .onSuccess {
                        toast("Snapshot restauré. Vérifiez la configuration puis redémarrez la VM.")
                        refresh()
                    }
                    .onFailure { toast("Restauration : " + (it.message ?: "échec")) }
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                RecoveryPlanStore.clear(filesDir, vm.id)
                toast("Plan effacé : le prochain démarrage utilisera la sélection automatique actuelle.")
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun chooseIso(vmId: String) {
        isoTargetVm = vmId
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/octet-stream"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, 701)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == 703) {
            Thread({
                val result = runCatching { RuntimeInstaller.installOptionalX86(this, uri) }
                handler.post {
                    result.onSuccess { toast("Runtime x86 installé et vérifié. Le guest Windows/Linux reste à valider sur cet appareil.") }
                        .onFailure { toast("Installation runtime x86 : " + (it.message ?: "échec")) }
                    refresh()
                }
            }, "phoenixvm-x86-addon-install").start()
            return
        }
        if (requestCode == 704 || requestCode == 705) {
            val toolId = if (requestCode == 704) "virtiofsd" else "swtpm"
            Thread({
                val result = runCatching { RuntimeInstaller.installOptionalTool(this, uri, toolId) }
                handler.post {
                    result.onSuccess { toast("Extension " + toolId + " installée et vérifiée.") }
                        .onFailure { toast("Installation " + toolId + " : " + (it.message ?: "échec")) }
                    refresh()
                }
            }, "phoenixvm-$toolId-addon-install").start()
            return
        }
        if (requestCode == 702) {
            importTrustedVmPackage(uri)
            return
        }
        if (requestCode != 701) return
        val vmId = isoTargetVm ?: return
        val target = File(filesDir, "vms/" + vmId + "/installer.iso").apply { parentFile?.mkdirs() }
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        }
        manager.setIso(vmId, target.absolutePath)
        refresh()
        toast("ISO importée et stockée localement.")
    }


    private fun configureGestures() {
        val current = com.assavendo.machinevirtuel.ui.GestureSettingsStore.load(this)
        val labels = arrayOf("Gestes activés", "Swipe bord", "Pinch", "Tap trois doigts")
        val checked = booleanArrayOf(current.enabled, current.edgeSwipeEnabled, current.pinchEnabled, current.threeFingerTapEnabled)
        AlertDialog.Builder(this)
            .setTitle("Gestes du viewer")
            .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setPositiveButton("Enregistrer") { _, _ ->
                com.assavendo.machinevirtuel.ui.GestureSettingsStore.save(
                    this,
                    com.assavendo.machinevirtuel.ui.GestureSettings(
                        checked[0], checked[1], checked[2], checked[3]
                    )
                )
                toast("Réglages gestes enregistrés.")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showDisplay(vmId: String) {
        val view = VmDisplayView(this)
        val dialog = AlertDialog.Builder(this).setTitle("Écran VM").setView(view).setPositiveButton("Fermer", null).create()
        dialog.setOnDismissListener { view.disconnect() }
        dialog.setOnShowListener { view.postDelayed({ view.connect(VmRuntimeService.vncPort(vmId)) }, 1200) }
        dialog.show()
    }

    private fun yesNo(value: Boolean) = if (value) "oui" else "non"
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun title(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(textColor); setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 8)
    }
    private fun subtitle(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, 8)
    }
    private fun cardText() = TextView(this).apply {
        textSize = 13f; setTextColor(textColor); setPadding(18, 18, 18, 18); background = rounded(panel)
    }
    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value; textSize = 15f; setTextColor(textColor); setTypeface(null, Typeface.BOLD); setPadding(2, 18, 2, 10)
    }
    private fun actionButton(label: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.WHITE); setBackgroundColor(color); setOnClickListener { action() }
    }
    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = 24f
    }
    private fun space(width: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
