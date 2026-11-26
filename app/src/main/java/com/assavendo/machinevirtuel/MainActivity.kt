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
import com.assavendo.machinevirtuel.performance.ResourceAdmissionController
import com.assavendo.machinevirtuel.runtime.SnapshotManager
import com.assavendo.machinevirtuel.runtime.UsbPassthroughManager
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
    private var pendingVmCreationDraft: VmConfig? = null
    private var pendingVmCreationArtifact: String? = null
    private var pendingVmCreationStep: Int = 0

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
            setTextColor(this@MainActivity.muted)
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
                VmState.ERROR, VmState.RECOVERY, VmState.SUSPENDED -> {
                    row1.addView(actionButton("Récupération", teal) { showRecovery(vm) })
                }
                else -> row1.addView(actionButton("Démarrer", good) { VmRuntimeService.start(this@MainActivity, vm.id) })
            }
            if (vm.state == VmState.OFF) {
                row1.addView(space(6))
                row1.addView(actionButton("ISO", violet) { chooseIso(vm.id) })
            }
            card.addView(row1)

            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (vm.state == VmState.OFF) {
                row2.addView(actionButton("Snapshot", teal) {
                    runCatching { manager.snapshot(vm.id, "manual") }
                        .onSuccess { toast("Snapshot créé.") }
                        .onFailure { toast("Snapshot : " + (it.message ?: "échec")) }
                })
                row2.addView(space(6))
                row2.addView(actionButton("Cloner", violet) { cloneVm(vm) })
            }
            row2.addView(actionButton("Snapshots", teal) { showSnapshots(vm) })
            row2.addView(space(6))
            row2.addView(actionButton("Optimiser", accent) { showOptimization(vm) })
            card.addView(row2)

            val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (vm.state == VmState.OFF) {
                row3.addView(actionButton("Exporter", accent) { exportVm(vm) })
                row3.addView(space(6))
                row3.addView(actionButton("Audio", teal) { showAudioSettings(vm) })
                row3.addView(space(6))
                row3.addView(actionButton("USB", violet) { showUsbPassthrough(vm) })
                row3.addView(space(6))
                row3.addView(actionButton("Supprimer", Color.rgb(95, 104, 118)) {
                    runCatching { manager.delete(vm.id) }
                        .onSuccess { toast("VM supprimée.") }
                        .onFailure { toast(it.message ?: "Suppression impossible.") }
                    refresh()
                })
            }
            row3.addView(actionButton("Rapport", teal) { exportDeveloperReport(vm) })
            row3.addView(space(6))
            row3.addView(actionButton("Métriques", accent) { showMetrics(vm) })
            card.addView(row3)

            vmContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, 12)
            })
        }
    }

    private fun createVm(initial: VmConfig? = null, initialStep: Int = 0) {
        val caps = manager.capabilities()
        val defaultRam = ResourceAdmissionController.check(
            VmConfig(name = "PhoenixVM", ramMb = 2048),
            manager.list(),
            caps,
        ).recommendedRamMb.coerceAtLeast(512)
        val base = initial ?: VmConfig(
            name = "Linux ARM64 VM",
            guestOs = "Linux ARM64",
            arch = GuestArch.ARM64,
            ramMb = defaultRam,
            vCpu = minOf(4, caps.cpuCount.coerceAtLeast(1)),
            autoSnapshotOnInstall = false,
        )
        val vmId = base.id
        pendingVmCreationDraft = base

        val stepPages = Array(7) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 8, 18, 8)
            }
        }
        var buildStep = 0
        var activeStep = initialStep.coerceIn(0, stepPages.lastIndex)
        pendingVmCreationStep = activeStep
        var content = stepPages[0]
        fun section(label: String) {
            buildStep = when {
                label.startsWith("B —") -> 1
                label.startsWith("C —") -> 2
                label.startsWith("D —") -> 3
                label.startsWith("E —") -> 4
                label.startsWith("F —") -> 5
                label.startsWith("G —") -> 6
                else -> 0
            }
            content = stepPages[buildStep]
            content.addView(TextView(this).apply {
                text = label
                setTextColor(violet)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 18, 0, 6)
            })
        }
        fun addLabel(label: String) {
            content.addView(TextView(this).apply {
                text = label
                setTextColor(textColor)
                textSize = 13f
                setPadding(0, 8, 0, 2)
            })
        }
        fun edit(value: String, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(this).apply {
                setText(value)
                setTextColor(textColor)
                setHintTextColor(muted)
                this.inputType = inputType
                content.addView(this)
            }
        }
        fun spinner(items: List<String>, selected: Int): Spinner {
            return Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    items,
                )
                setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
                content.addView(this)
            }
        }
        fun checkBox(label: String, checked: Boolean): CheckBox {
            return CheckBox(this).apply {
                text = label
                setTextColor(textColor)
                isChecked = checked
                content.addView(this)
            }
        }

        val osChoice = when {
            base.guestOs.contains("windows", true) -> GuestOsChoice.WINDOWS
            base.guestOs.contains("android", true) -> GuestOsChoice.ANDROID
            base.guestOs.contains("bsd", true) -> GuestOsChoice.BSD
            base.guestOs.contains("dos", true) || base.guestOs.contains("legacy", true) -> GuestOsChoice.DOS_LEGACY
            base.guestOs.contains("custom iso", true) -> GuestOsChoice.CUSTOM_ISO
            base.guestOs.contains("existing disk", true) -> GuestOsChoice.EXISTING_DISK
            else -> GuestOsChoice.LINUX
        }
        section("Identité de la machine")
        addLabel("Nom de la VM")
        val vmName = edit(base.name)

        section("A — Architecture")
        addLabel("Architecture invitée")
        val architectureSpinner = Spinner(this)
        content.addView(architectureSpinner)

        section("B — Système invité et médias")
        content.addView(subtitle(
            "Linux ARM64 est la cible prioritaire. Les guests x86_64/i386 nécessitent l'extension QEMU x86 ; Windows, Android et BSD restent à valider avec leurs médias et pilotes réels."
        ))
        addLabel("Système invité")
        val osSpinner = spinner(GuestOsChoice.entries.map { it.label }, GuestOsChoice.entries.indexOf(osChoice))
        fun updateArchitectures(preferred: GuestArch?) {
            val choice = GuestOsChoice.entries[osSpinner.selectedItemPosition]
            val allowed = choice.allowedArchitectures().toList()
            architectureSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                allowed.map { it.label },
            )
            val selected = preferred?.takeIf { it in allowed } ?: choice.defaultArchitecture
            architectureSpinner.setSelection(allowed.indexOf(selected).coerceAtLeast(0))
        }
        updateArchitectures(base.arch)
        osSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                val old = GuestArch.entries.firstOrNull { it.label == architectureSpinner.selectedItem?.toString() }
                updateArchitectures(old)
            }
        }
        addLabel("Version / description (facultatif)")
        val version = edit(base.guestVersion)
        val artifactRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(subtitle(
            "Supports : ISO personnalisée, disque existant et images kernel/initrd/DTB/system/vendor/userdata pour Android. Les fichiers sont copiés dans l'espace privé de la VM."
        ))
        content.addView(artifactRow)
        val androidAdb = checkBox("Activer ADB réseau (Android invité uniquement)", base.androidAdbEnabled)
        addLabel("Port hôte ADB")
        val adbPort = edit(base.androidAdbHostPort.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        val guestAgent = checkBox(
            "Activer QEMU Guest Agent (Linux invité uniquement)",
            base.guestAgentEnabled,
        )
        content.addView(subtitle(
            "Permet de relever CPU, attente I/O et interruptions depuis Linux. Le paquet qemu-guest-agent doit être installé et démarré dans l'invité (par exemple : sudo apt install qemu-guest-agent && sudo systemctl enable --now qemu-guest-agent)."
        ))

        section("C — Ressources de la machine")
        addLabel("RAM (MiB)")
        val ram = edit(base.ramMb.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        addLabel("Nombre de vCPU")
        val vcpus = edit(base.vCpu.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        addLabel("Capacité du nouveau disque (GiB)")
        val diskSize = edit(base.diskSizeGiB.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        content.addView(subtitle("Cette capacité est utilisée pour un disque neuf. Un disque importé conserve la capacité définie dans son image ; PhoenixVM n'en exécute pas le parseur de métadonnées à l'import."))
        addLabel("Format du disque")
        val diskFormats = DiskFormat.entries
        val diskFormat = spinner(diskFormats.map { it.name }, diskFormats.indexOf(base.diskFormat))

        section("D — GPU et affichage")
        addLabel("Backend graphique")
        val gpuBackends = GpuBackend.entries
        val gpuLabels = listOf(
            "AUTO — automatique / compatibilité",
            "SOFTWARE — rendu logiciel",
            "VIRGL — rendu accéléré VirGL",
            "GFXSTREAM — rendu accéléré Gfxstream",
        )
        val gpu = spinner(
            gpuLabels,
            gpuBackends.indexOf(base.gpuBackend),
        )
        content.addView(subtitle(
            "AUTO choisit le rendu logiciel tant qu'une accélération complète n'est pas vérifiée. " +
                "VirGL/Gfxstream ne garantissent pas que le pilote du téléphone accepte le chemin."
        ))

        section("E — Réseau et connectivité")
        addLabel("Mode réseau")
        val networkModes = NetworkMode.entries
        val networkLabels = listOf(
            "DISABLED — aucun réseau",
            "NAT — réseau utilisateur",
            "TAP — bridge/root si possible",
        )
        val network = spinner(networkLabels, networkModes.indexOf(base.networkMode))
        addLabel("Redirections (TCP:port_hôte:port_invité, UDP:port_hôte:port_invité ou port_hôte:port_invité)")
        val forwards = edit(
            base.portForwards.joinToString(",") { "${it.protocol.uppercase()}:${it.hostPort}:${it.guestPort}" }
        )
        addLabel("Nom de l'interface TAP (mode TAP uniquement)")
        val tapName = edit(base.tapName)
        content.addView(subtitle("TAP tente de préparer l'interface avec les privilèges disponibles, mais dépend du root, de SELinux et du support OEM. Les redirections de ports hostfwd sont réservées à NAT ; elles sont refusées en TAP."))

        section("F — Partage hôte / invité")
        val shareDirectory = File(filesDir, "vms/$vmId/shared").canonicalFile
        val share = checkBox("Créer le dossier partagé PhoenixVM", base.sharedDirs.isNotEmpty() || base.sharedDirPath.isNotBlank())
        val shareLocation = cardText().apply {
            text = "Dossier privé de partage :\n${shareDirectory.absolutePath}\n" +
                "Les fichiers placés dans ce dossier sont exposés au guest via le tag « shared »."
        }
        content.addView(shareLocation)
        content.addView(subtitle(
            "Les fichiers ponctuels peuvent être déposés dans ce dossier partagé. Le presse-papiers hôte/invité n'est pas encore relié à un agent guest et reste désactivé."
        ))

        section("G — Optimisation et sécurité")
        addLabel("Mode")
        val modes = VmMode.entries.filter { it != VmMode.EXPERT }
        val mode = spinner(modes.map { it.label }, modes.indexOf(base.mode))
        content.addView(subtitle(
            "AUTO sélectionne le profil adaptatif ; PERFORMANCE vise le débit ; le profil Battery Saver active le preset économie d'énergie demandé par le cahier des charges."
        ))
        addLabel("Profil de performance")
        val presets = PerformancePreset.entries
        val preset = spinner(presets.map { it.label }, presets.indexOf(base.performancePreset))
        val secureBoot = checkBox("Secure Boot (x86_64 uniquement)", base.secureBoot)
        fun updateSecureBootAvailability() {
            val selected = GuestArch.entries.firstOrNull { it.label == architectureSpinner.selectedItem?.toString() }
            secureBoot.isEnabled = selected == GuestArch.X86_64
            if (!secureBoot.isEnabled) secureBoot.isChecked = false
        }
        architectureSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                updateSecureBootAvailability()
            }
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                updateSecureBootAvailability()
            }
        }
        updateSecureBootAvailability()
        val autoSnapshot = checkBox("Créer un snapshot froid initial", base.autoSnapshotOnInstall)
        fun updateAndroidControls() {
            val isAndroid = GuestOsChoice.entries[osSpinner.selectedItemPosition] == GuestOsChoice.ANDROID
            androidAdb.isEnabled = isAndroid
            if (!isAndroid) androidAdb.isChecked = false
            adbPort.isEnabled = isAndroid && androidAdb.isChecked
            val isLinux = GuestOsChoice.entries[osSpinner.selectedItemPosition] == GuestOsChoice.LINUX
            guestAgent.isEnabled = isLinux
            if (!isLinux) guestAgent.isChecked = false
        }
        osSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                updateAndroidControls()
            }
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                val previous = GuestArch.entries.firstOrNull {
                    it.label == architectureSpinner.selectedItem?.toString()
                }
                updateArchitectures(previous)
                updateAndroidControls()
            }
        }
        androidAdb.setOnCheckedChangeListener { _, _ -> updateAndroidControls() }
        updateAndroidControls()

        lateinit var dialog: AlertDialog
        fun currentDraft(): VmConfig {
            val selectedOs = GuestOsChoice.entries[osSpinner.selectedItemPosition]
            val allowed = selectedOs.allowedArchitectures().toList()
            val selectedArch = allowed[architectureSpinner.selectedItemPosition.coerceIn(0, allowed.lastIndex)]
            val config = base.copy(
                name = vmName.text.toString().trim(),
                description = base.description,
                guestOs = selectedOs.guestOs(selectedArch),
                guestVersion = version.text.toString().trim(),
                arch = selectedArch,
                ramMb = ram.text.toString().toIntOrNull() ?: -1,
                vCpu = vcpus.text.toString().toIntOrNull() ?: -1,
                diskSizeGiB = diskSize.text.toString().toLongOrNull() ?: -1L,
                diskFormat = diskFormats[diskFormat.selectedItemPosition],
                gpuBackend = gpuBackends[gpu.selectedItemPosition],
                networkMode = networkModes[network.selectedItemPosition],
                portForwards = VmCreationPolicy.parsePortForwards(forwards.text.toString()),
                tapName = tapName.text.toString().trim(),
                mode = modes[mode.selectedItemPosition],
                performancePreset = presets[preset.selectedItemPosition],
                secureBoot = secureBoot.isChecked,
                autoSnapshotOnInstall = autoSnapshot.isChecked,
                androidAdbEnabled = selectedOs == GuestOsChoice.ANDROID && androidAdb.isChecked,
                androidAdbHostPort = adbPort.text.toString().toIntOrNull() ?: -1,
                guestAgentEnabled = selectedOs == GuestOsChoice.LINUX && guestAgent.isChecked,
                androidSystemPath = if (selectedOs == GuestOsChoice.ANDROID) base.androidSystemPath else "",
                androidVendorPath = if (selectedOs == GuestOsChoice.ANDROID) base.androidVendorPath else "",
                androidUserdataPath = if (selectedOs == GuestOsChoice.ANDROID) base.androidUserdataPath else "",
                sharedDirPath = "",
                sharedDirs = if (share.isChecked) {
                    check(shareDirectory.mkdirs() || shareDirectory.isDirectory) {
                        "Impossible de préparer le dossier partagé privé."
                    }
                    VmCreationPolicy.sharedDirectories(listOf(shareDirectory.absolutePath))
                } else {
                    emptyList()
                },
                headless = false,
            )
            val capabilities = caps
            val suggested = VmOrchestrator().choose(config.copy(mode = VmMode.AUTO), capabilities).selected
            config.backend = when (suggested) {
                BackendKind.GUNYAH, BackendKind.GENIEZONE, BackendKind.HALLA, BackendKind.AVF ->
                    if (capabilities.kvm) BackendKind.KVM else BackendKind.QEMU_TCG
                else -> suggested
            }
            if (config.arch != GuestArch.X86_64) config.secureBoot = false
            return config
        }
        fun pickArtifact(kind: String, requestCode: Int) {
            val draft = runCatching { currentDraft() }.getOrElse {
                toast(it.message ?: "Configuration invalide.")
                return
            }
            pendingVmCreationDraft = draft
            pendingVmCreationArtifact = kind
            pendingVmCreationStep = activeStep
            dialog.dismiss()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                // ISO, VHD/QCOW and kernel images are exposed with different provider MIME types.
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                if (requestCode == 714) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, requestCode)
        }
        fun artifactButton(label: String, kind: String, requestCode: Int) {
            artifactRow.addView(actionButton(label, teal) { pickArtifact(kind, requestCode) })
        }
        artifactButton("Choisir l'ISO personnalisée (obligatoire pour ce profil)", "iso", 706)
        artifactButton("Ajouter une ISO d'installation (Linux / Windows / BSD)", "iso", 715)
        artifactButton("Importer un disque existant", "disk", 707)
        artifactButton("Choisir le kernel Android / Linux", "kernel", 708)
        artifactButton("Choisir l'initrd (facultatif)", "initrd", 709)
        artifactButton("Choisir le DTB (facultatif)", "dtb", 710)
        artifactButton("Choisir Android system.img", "android-system", 711)
        artifactButton("Choisir Android vendor.img (facultatif)", "android-vendor", 712)
        artifactButton("Choisir Android userdata.img", "android-userdata", 713)
        artifactButton("Ajouter des fichiers ponctuels au dossier partagé", "shared-files", 714)
        stepPages[1].addView(cardText().apply {
            text = buildString {
                append("ISO : ").append(base.isoPath.takeIf { it.isNotBlank() }?.let { File(it).name } ?: "non sélectionnée").append('\n')
                append("Disque existant : ").append(base.diskPath.takeIf { it.isNotBlank() }?.let { File(it).name } ?: "aucun").append('\n')
                append("Kernel : ").append(base.kernelPath.takeIf { it.isNotBlank() }?.let { File(it).name } ?: "aucun").append('\n')
                append("Android system : ").append(base.androidSystemPath.takeIf { it.isNotBlank() }?.let { File(it).name } ?: "aucun").append('\n')
                append("Android userdata : ").append(base.androidUserdataPath.takeIf { it.isNotBlank() }?.let { File(it).name } ?: "aucun")
            }
        })

        val stepTitles = VmCreationWizardStep.entries.map { it.title }
        val progressLabel = TextView(this).apply {
            setTextColor(violet)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(18, 14, 18, 8)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val wizardRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progressLabel)
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    -1,
                    (resources.displayMetrics.heightPixels * 0.55f).toInt().coerceAtLeast(320),
                ),
            )
        }
        fun renderStep() {
            scroll.removeAllViews()
            scroll.addView(stepPages[activeStep])
            progressLabel.text = "Étape ${activeStep + 1}/7 — ${stepTitles[activeStep]}"
        }
        renderStep()

        dialog = AlertDialog.Builder(this)
            .setTitle("Assistant de création VM — 7 étapes")
            .setView(wizardRoot)
            .setNegativeButton("Annuler", null)
            .setNeutralButton("Précédent", null)
            .setPositiveButton("Suivant", null)
            .create()
        dialog.setOnShowListener {
            val previousButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            val nextButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            fun updateNavigation() {
                previousButton.isEnabled = activeStep > 0
                nextButton.text = if (activeStep == stepPages.lastIndex) "Créer la VM" else "Suivant"
            }

            previousButton.setOnClickListener {
                if (activeStep > 0) {
                    activeStep--
                    pendingVmCreationStep = activeStep
                    renderStep()
                    updateNavigation()
                }
            }

            nextButton.setOnClickListener {
                if (activeStep < stepPages.lastIndex) {
                    activeStep++
                    pendingVmCreationStep = activeStep
                    renderStep()
                    updateNavigation()
                    return@setOnClickListener
                }

                val config = runCatching { currentDraft() }.getOrElse {
                    activeStep = 4
                    renderStep()
                    updateNavigation()
                    toast(it.message ?: "Configuration invalide.")
                    return@setOnClickListener
                }
                if (config.name.isBlank()) {
                    activeStep = 0
                    renderStep()
                    updateNavigation()
                    toast("Le nom de la VM ne peut pas être vide.")
                    return@setOnClickListener
                }

                val issues = VmSpecValidator.validate(config).filter { it.fatal }
                if (issues.isNotEmpty()) {
                    activeStep = VmCreationWizardStep.forValidationField(issues.first().field).ordinal
                    renderStep()
                    updateNavigation()
                    toast(issues.joinToString("\n") { it.field + " : " + it.message })
                    return@setOnClickListener
                }

                val existing = manager.list()
                if (existing.any { it.name.equals(config.name, ignoreCase = true) }) {
                    activeStep = 0
                    renderStep()
                    updateNavigation()
                    toast("Une VM porte déjà ce nom. Choisis un nom unique.")
                    return@setOnClickListener
                }

                val admission = ResourceAdmissionController.check(config, existing, caps)
                if (!admission.allowed) {
                    activeStep = 2
                    renderStep()
                    updateNavigation()
                    toast(admission.reason)
                    return@setOnClickListener
                }

                pendingVmCreationDraft = config
                pendingVmCreationStep = 2
                dialog.dismiss()
                Toast.makeText(this, "Création de la VM en cours…", Toast.LENGTH_SHORT).show()
                Thread({
                    val result = runCatching { manager.create(config) }
                    handler.post {
                        result.onSuccess {
                            pendingVmCreationDraft = null
                            pendingVmCreationArtifact = null
                            pendingVmCreationStep = 0
                            toast("VM créée : ${it.name}. Le démarrage invité reste à valider.")
                        }.onFailure {
                            pendingVmCreationDraft = config
                            pendingVmCreationStep = 2
                            toast("Création impossible : " + (it.message ?: "erreur inconnue"))
                            createVm(config, 2)
                        }
                        refresh()
                    }
                }, "phoenixvm-vm-create").start()
            }
            updateNavigation()
        }
        dialog.show()
    }    private fun cloneVm(vm: VmConfig) {
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
        val canApply = vm.state == VmState.OFF
        val builder = AlertDialog.Builder(this)
            .setTitle("Auto Optimizer — " + vm.name)
            .setMessage(
                "Backend : " + advice.currentBackend + "\n" +
                    "vCPU recommandé : " + advice.recommendedVcpus + "\n" +
                    "RAM recommandée : " + advice.recommendedRamMb + " MB\n" +
                    "GPU : " + advice.recommendedGpuMode + "\n" +
                    "Thermique : " + advice.thermalState + "\n\n" +
                    advice.actions.joinToString("\n") +
                    if (canApply) "\n\nLes ressources vCPU/RAM et la fréquence de monitoring peuvent être appliquées à cette VM arrêtée." else
                        "\n\nLa VM doit être arrêtée pour appliquer automatiquement ces changements."
            )
            .setNegativeButton("Fermer", null)
        if (canApply) {
            builder.setPositiveButton("Analyser et appliquer") { _, _ ->
                runCatching { manager.applyOptimization(vm.id) }
                    .onSuccess {
                        toast("Optimisation appliquée. Vérifiez le benchmark avant/après.")
                        refresh()
                    }
                    .onFailure { toast("Optimisation : " + (it.message ?: "échec")) }
            }
        }
        builder.show()
    }

    private fun showAudioSettings(vm: VmConfig) {
        if (vm.state != VmState.OFF) {
            toast("La VM doit être arrêtée pour modifier l'audio.")
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 0, 18, 0)
        }
        val enabled = CheckBox(this).apply {
            text = "Audio VirtIO / AAudio activé"
            setTextColor(textColor)
            isChecked = vm.audioEnabled
        }
        val muteToggle = CheckBox(this).apply {
            text = "Mute"
            setTextColor(textColor)
            isChecked = vm.audioMuted
        }
        val frequency = EditText(this).apply {
            hint = "Fréquence Hz"
            setText(vm.audioFrequencyHz.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(textColor)
        }
        val channels = EditText(this).apply {
            hint = "Canaux"
            setText(vm.audioChannels.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(textColor)
        }
        val buffer = EditText(this).apply {
            hint = "Buffer ms / latence configurée"
            setText(vm.audioBufferMs.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(textColor)
        }
        layout.addView(enabled)
        layout.addView(muteToggle)
        layout.addView(TextView(this).apply { text = "Fréquence"; setTextColor(textColor) })
        layout.addView(frequency)
        layout.addView(TextView(this).apply { text = "Canaux"; setTextColor(textColor) })
        layout.addView(channels)
        layout.addView(TextView(this).apply { text = "Buffer / latence configurée"; setTextColor(textColor) })
        layout.addView(buffer)
        layout.addView(TextView(this).apply {
            text = "Synchronisation AV : non mesurée sans télémétrie audio/guest adaptée."
            setTextColor(muted)
            textSize = 12f
            setPadding(0, 12, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Audio — " + vm.name)
            .setView(layout)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer") { _, _ ->
                runCatching {
                    manager.updateAudio(
                        vm.id,
                        enabled.isChecked,
                        muteToggle.isChecked,
                        frequency.text.toString().toInt(),
                        channels.text.toString().toInt(),
                        buffer.text.toString().toInt(),
                    )
                }.onSuccess {
                    toast("Configuration audio enregistrée. Elle prendra effet au prochain démarrage.")
                    refresh()
                }.onFailure {
                    toast(it.message ?: "Configuration audio invalide.")
                }
            }
            .show()
    }

    private fun showUsbPassthrough(vm: VmConfig) {
        val attached = vm.usbPassthrough
        val devices = UsbPassthroughManager.list(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 8, 4)
        }

        body.addView(TextView(this).apply {
            text = "Périphériques attachés : " + attached.size + "/8"
            setTextColor(textColor)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })

        attached.forEach { config ->
            body.addView(TextView(this).apply {
                text = "ATTACHÉ 0x%04x:0x%04x".format(config.vendorId, config.productId) +
                    if (config.serialNumber.isBlank()) "" else " • série=" + config.serialNumber
                setTextColor(good)
                setPadding(0, 6, 0, 2)
            })
            body.addView(actionButton("Détacher", bad) {
                runCatching { manager.detachUsb(vm.id, config) }
                    .onSuccess {
                        toast("Périphérique USB détaché de la VM.")
                        refresh()
                        showUsbPassthrough(vm)
                    }
                    .onFailure { toast(it.message ?: "Détachement impossible.") }
            })
        }

        if (devices.isEmpty()) {
            body.addView(cardText().apply {
                text = "Aucun périphérique USB hôte détecté par Android."
            })
        } else {
            devices.forEach { device ->
                val display = buildString {
                    append("0x%04x:0x%04x".format(device.vendorId, device.productId))
                    device.productName?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
                    device.manufacturerName?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
                    device.serialNumber?.takeIf { it.isNotBlank() }?.let { append(" • série=").append(it) }
                    append("\n").append(device.deviceName)
                    append(" • permission=").append(if (device.hasPermission) "oui" else "non")
                    append(" • accès direct=").append(if (device.directDeviceAccess) "oui" else "non")
                    if (device.isHub) append(" • HUB refusé")
                    if (!device.directDeviceAccess && com.assavendo.machinevirtuel.runtime.RootProcessLauncher.hasRoot()) {
                        append(" • root disponible")
                    }
                }
                body.addView(TextView(this).apply {
                    text = display
                    setTextColor(if (device.isHub) bad else textColor)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 10, 0, 4)
                })
                if (!device.isHub) {
                    val action = actionButton(
                        if (device.hasPermission) "Attacher" else "Autoriser puis attacher",
                        accent,
                    ) {
                        val usbDevice = UsbPassthroughManager.findDevice(this, device.deviceName)
                        if (usbDevice == null) {
                            toast("Périphérique USB disparu.")
                        } else {
                            val attach = {
                                val config = UsbPassthroughConfig(
                                    vendorId = device.vendorId,
                                    productId = device.productId,
                                    serialNumber = device.serialNumber.orEmpty(),
                                    guestReset = false,
                                )
                                runCatching { manager.attachUsb(vm.id, config) }
                                    .onSuccess {
                                        toast("USB attaché à la VM. Le passage hôte sera validé au prochain démarrage.")
                                        refresh()
                                    }
                                    .onFailure { toast(it.message ?: "Attachement impossible.") }
                            }
                            if (device.hasPermission) {
                                attach()
                            } else {
                                UsbPassthroughManager.requestPermission(this, usbDevice) { granted ->
                                    runOnUiThread {
                                        if (granted) attach()
                                        else toast("Permission USB refusée.")
                                    }
                                }
                            }
                        }
                    }
                    body.addView(action)
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("USB hôte — " + vm.name)
            .setView(body)
            .setMessage(
                "Seuls les périphériques sélectionnés pour cette VM peuvent être passés à QEMU. " +
                    "Les hubs sont refusés. L'accès direct ou root reste obligatoire au démarrage."
            )
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun showMetrics(vm: VmConfig) {
        val latest = com.assavendo.machinevirtuel.performance.VmMetricsRegistry.last(vm.id, 1).lastOrNull()
        val windows = com.assavendo.machinevirtuel.performance.VmMetricsRegistry.windows(vm.id)

        fun value(value: Double?, suffix: String = "") =
            value?.let { "%.2f".format(it) + suffix } ?: "indisponible"

        val body = buildString {
            append("VM : ").append(vm.name).append("\n")
            append("FPS : ").append(value(latest?.fps)).append("\n")
            append("CPU host : ").append(value(latest?.cpuHostPercent, " %")).append("\n")
            append("CPU guest (moyenne des cœurs) : ")
                .append(value(latest?.cpuGuestPercent, " %"))
                .append(latest?.guestCpuCoreCount?.let { " — $it cœur(s)" } ?: "")
                .append("\n")
            append("Attente I/O guest : ").append(value(latest?.guestCpuIoWaitPercent, " %")).append("\n")
            append("Interruptions guest : ").append(value(latest?.guestCpuInterruptPercent, " %")).append("\n")
            append("Temps CPU guest (échantillon) : ")
                .append(
                    if (latest?.guestCpuTimeDeltaMs != null && latest.guestCpuIntervalMs != null) {
                        latest.guestCpuTimeDeltaMs.toString() + " ms sur " + latest.guestCpuIntervalMs + " ms"
                    } else "indisponible"
                )
                .append("\n")
            append("Attente I/O hôte : ").append(value(latest?.systemCpuIoWaitPercent, " %")).append("\n")
            append("vCPU host-thread : ").append(value(latest?.vCpuUtilization, " %")).append("\n")
            append("Température : ").append(latest?.temperatureC?.let { "%.1f °C".format(it) } ?: "indisponible").append("\n")
            append("I/O disque système — lecture : ").append(latest?.ioReadBytesPerSec?.let(::formatRate) ?: "indisponible").append("\n")
            append("I/O disque système — écriture : ").append(latest?.ioWriteBytesPerSec?.let(::formatRate) ?: "indisponible").append("\n")
            append("I/O backend VM — lecture : ").append(latest?.processIoReadBytesPerSec?.let(::formatRate) ?: "indisponible").append("\n")
            append("I/O backend VM — écriture : ").append(latest?.processIoWriteBytesPerSec?.let(::formatRate) ?: "indisponible").append("\n")
            append("Intervalle de mesure : ").append(latest?.sampleIntervalMs?.let { "$it ms" } ?: "indisponible").append("\n")
            append("Réseau RX : ").append(latest?.networkRxBytesPerSec?.let(::formatRate) ?: "indisponible").append("\n")
            append("Réseau TX : ").append(latest?.networkTxBytesPerSec?.let(::formatRate) ?: "indisponible").append("\n")
            append("Cache traduction : ").append(latest?.cacheHitRate?.let { "%.1f".format(it * 100.0) + " %" } ?: "indisponible").append("\n")
            append("Temps traduction moyen : ").append(latest?.translationMs?.let { "%.3f ms".format(it) } ?: "indisponible").append("\n")
            append("GPU : indisponible si le chemin n'est pas exposé par le backend/hôte").append("\n")
            append("Audio underruns : ")
                .append(latest?.audioUnderruns?.toString() ?: "indisponible")
                .append("\n")
            append("Latence audio configurée : ")
                .append(latest?.audioLatencyConfiguredMs?.let { "%.1f ms".format(it) } ?: "indisponible")
                .append("\n")
            append("Synchronisation AV : non mesurée sans télémétrie guest/audio adaptée")
                .append("\n\n")
            append("Fenêtres 1/5/30 min :\n")
            windows.forEach { window ->
                append(window.minutes).append(" min — ")
                append("échantillons=").append(window.samples)
                append(", FPS=").append(value(window.fpsAvg))
                append(", CPU host=").append(value(window.cpuHostAvg, " %"))
                append(", CPU guest=").append(value(window.cpuGuestAvg, " %"))
                append(", attente I/O guest=").append(value(window.guestCpuIoWaitAvgPercent, " %"))
                append(", IRQ guest=").append(value(window.guestCpuInterruptAvgPercent, " %"))
                append(", attente I/O hôte=").append(value(window.systemCpuIoWaitPercentAvg, " %"))
                append(", I/O backend lecture=").append(window.processIoReadBytesPerSecAvg?.let { formatRate(it.toLong()) } ?: "indisponible")
                append(", I/O backend écriture=").append(window.processIoWriteBytesPerSecAvg?.let { formatRate(it.toLong()) } ?: "indisponible")
                append(", vCPU=").append(value(window.vCpuUtilizationAvg, " %"))
                append(", cache=").append(
                    window.cacheHitRateAvg?.let { "%.1f".format(it * 100.0) + " %" } ?: "indisponible"
                )
                append(", underruns=").append(window.audioUnderrunsLast?.toString() ?: "indisponible")
                append(", latence audio=").append(
                    window.audioLatencyConfiguredMsAvg?.let { "%.1f ms".format(it) } ?: "indisponible"
                )
                append("\n")
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Monitoring — " + vm.name)
            .setMessage(body)
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun formatRate(bytesPerSecond: Long): String {
        val units = arrayOf("B/s", "KiB/s", "MiB/s", "GiB/s")
        var value = bytesPerSecond.toDouble().coerceAtLeast(0.0)
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return "%.1f %s".format(value, units[unit])
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
            .setMultiChoiceItems(labels, BooleanArray(labels.size) { true }) { _, which, checked ->
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
        if (requestCode in 706..715) {
            val draft = pendingVmCreationDraft
            val kind = pendingVmCreationArtifact
            val hasSelectedFile = data?.data != null || (requestCode == 714 && data?.clipData != null)
            if (resultCode != RESULT_OK || data == null || !hasSelectedFile) {
                pendingVmCreationArtifact = null
                if (draft != null) createVm(draft, pendingVmCreationStep)
                return
            }
            if (draft == null || kind == null) {
                toast("Brouillon de création introuvable ; recommence la sélection du fichier.")
                return
            }
            val selectedUri = data.data ?: data.clipData?.getItemAt(0)?.uri
                ?: error("Aucun fichier valide n'a été sélectionné.")
            val selectedKind = kind
            Thread({
                val result = runCatching {
                    if (selectedKind == "shared-files") {
                        val uris = buildList {
                            val clip = data.clipData
                            if (clip != null) {
                                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
                            } else {
                                add(selectedUri)
                            }
                        }
                        require(uris.isNotEmpty()) { "Aucun fichier n'a été sélectionné." }
                        copyVmCreationSharedFiles(draft, uris)
                    } else {
                        copyVmCreationArtifact(draft, selectedKind, selectedUri)
                    }
                }
                handler.post {
                    pendingVmCreationArtifact = null
                    result.onSuccess { updated ->
                        pendingVmCreationDraft = updated
                        toast(
                            if (selectedKind == "shared-files") "Fichiers ajoutés au partage de cette VM."
                            else "Fichier importé et copié dans le stockage privé PhoenixVM."
                        )
                        createVm(updated, pendingVmCreationStep)
                    }.onFailure { error ->
                        toast("Import du fichier impossible : " + (error.message ?: "erreur inconnue"))
                        createVm(draft, pendingVmCreationStep)
                    }
                }
            }, "phoenixvm-create-artifact").start()
            return
        }

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



    private fun copyVmCreationArtifact(
        draft: VmConfig,
        kind: String,
        uri: android.net.Uri,
    ): VmConfig {
        val root = File(filesDir, "vms").canonicalFile
        val directory = File(root, draft.id).canonicalFile
        require(directory.toPath().startsWith(root.toPath()) && directory != root) {
            "Identifiant ou chemin de brouillon de VM invalide."
        }
        check(directory.mkdirs() || directory.isDirectory) {
            "Impossible de préparer le stockage privé de la VM."
        }
        val targetName = when (kind) {
            "iso" -> "installer.iso"
            "disk" -> "disk." + com.assavendo.machinevirtuel.runtime.DiskManager.extension(draft.diskFormat)
            "kernel" -> "kernel.Image"
            "initrd" -> "initrd"
            "dtb" -> "guest.dtb"
            "android-system" -> "android-system.img"
            "android-vendor" -> "android-vendor.img"
            "android-userdata" -> "android-userdata.img"
            else -> error("Type de fichier de création inconnu.")
        }
        val target = File(directory, targetName)
        val temporary = File(directory, targetName + ".partial")
        temporary.delete()
        val reservedBytes = 64L * 1024L * 1024L
        try {
            val input = contentResolver.openInputStream(uri)
                ?: error("Android n'a pas pu ouvrir le fichier sélectionné.")
            input.use { source ->
                java.io.FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        require(directory.usableSpace >= count.toLong() + reservedBytes) {
                            "Espace insuffisant pour importer $targetName; 64 Mio sont conservés en réserve."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(temporary.isFile && temporary.length() > 0L) {
                "Le fichier sélectionné est vide ou illisible."
            }
            // Same-filesystem rename keeps the previous file intact until the new copy is complete.
            android.system.Os.rename(temporary.absolutePath, target.absolutePath)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        val updated = draft.copy()
        when (kind) {
            "iso" -> updated.isoPath = target.absolutePath
            "disk" -> updated.diskPath = target.absolutePath
            "kernel" -> updated.kernelPath = target.absolutePath
            "initrd" -> updated.initrdPath = target.absolutePath
            "dtb" -> updated.dtbPath = target.absolutePath
            "android-system" -> updated.androidSystemPath = target.absolutePath
            "android-vendor" -> updated.androidVendorPath = target.absolutePath
            "android-userdata" -> updated.androidUserdataPath = target.absolutePath
        }
        return updated
    }

    private fun copyVmCreationSharedFiles(
        draft: VmConfig,
        uris: List<android.net.Uri>,
    ): VmConfig {
        require(uris.size <= 64) { "Au maximum 64 fichiers peuvent être ajoutés en une fois." }
        val vmRoot = File(filesDir, "vms").canonicalFile
        val vmDir = File(vmRoot, draft.id).canonicalFile
        require(vmDir.toPath().startsWith(vmRoot.toPath()) && vmDir != vmRoot) {
            "Identifiant ou chemin de VM invalide."
        }
        val shareDir = File(vmDir, "shared").canonicalFile
        require(shareDir.toPath().startsWith(vmDir.toPath()) && shareDir != vmDir) {
            "Chemin du dossier partagé invalide."
        }
        check(shareDir.mkdirs() || shareDir.isDirectory) {
            "Impossible de préparer le dossier partagé de la VM."
        }
        val committedFiles = mutableListOf<File>()
        val reservedBytes = 64L * 1024L * 1024L
        try {
            uris.forEachIndexed { index, uri ->
                val displayName = contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                val rawName = displayName?.takeIf { it.isNotBlank() } ?: "shared-file-$index"
                val safeName = rawName
                    .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    .replace(Regex("\\s+"), "_")
                    .trim('.', ' ', '_')
                    .take(120)
                    .ifBlank { "shared-file-$index" }
                val dot = safeName.lastIndexOf('.')
                val stem = if (dot > 0) safeName.substring(0, dot) else safeName
                val extension = if (dot > 0) safeName.substring(dot) else ""
                var target = File(shareDir, safeName)
                var suffix = 2
                while (target.exists()) {
                    val suffixText = "-$suffix"
                    target = File(
                        shareDir,
                        stem.take(120 - suffixText.length - extension.length) + suffixText + extension,
                    )
                    suffix++
                }
                require(target.canonicalFile.parentFile == shareDir) {
                    "Nom de fichier partagé invalide."
                }
                val temporary = File(shareDir, target.name + ".partial")
                temporary.delete()
                try {
                    val input = contentResolver.openInputStream(uri)
                        ?: error("Android n'a pas pu ouvrir le fichier partagé.")
                    input.use { source ->
                        java.io.FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                require(shareDir.usableSpace >= count.toLong() + reservedBytes) {
                                    "Espace insuffisant pour le fichier partagé $safeName; 64 Mio sont conservés en réserve."
                                }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    require(temporary.isFile && temporary.length() > 0L) {
                        "Le fichier partagé $safeName est vide ou illisible."
                    }
                    android.system.Os.rename(temporary.absolutePath, target.absolutePath)
                    committedFiles += target
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
            }
        } catch (error: Throwable) {
            // Multi-select is transactional: do not leave a partially imported batch.
            committedFiles.forEach { it.delete() }
            throw error
        }
        val share = SharedDirectory(shareDir.absolutePath, "shared", readOnly = false)
        return draft.copy(
            sharedDirPath = "",
            sharedDirs = (draft.sharedDirs.filterNot { it.tag == "shared" } + share),
        )
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
        val view = VmDisplayView(this, vmId)
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
