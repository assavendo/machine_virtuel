# PhoenixVM — implementation status against the source cahier

## Dernière avancée section 9 — codec IR v3 à schéma fermé

Le codec PVTCGIR est passé en version 3. La validation est désormais fail-closed : seuls les opcodes et formes d'opérandes explicitement décrits (marqueurs insn_start, opérations scalaires I32/I64 de la petite liste blanche, branches simples, goto_tb et exit_tb) sont acceptés. Les marqueurs insn_start doivent contenir exactement trois mots ; les sorties TB sont représentées par un indice abstrait QEMU autorisé, jamais par l'adresse TB de l'ancien processus. Les opérations non décrites, helpers et paramètres non pris en charge sont des rejets sûrs.

Les globals sont sérialisés par index numérique dans TCGContext.temps ; le digest tb_context_sha256 doit inclure la disposition canonique complète des globals/fixed temps. Le codec vérifie les bornes d'index, mais le calcul et la comparaison de ce digest appartiendront encore à l'adaptateur C QEMU, qui n'est pas implémenté. Le codec v3 reste donc une brique de format/stockage, pas un importeur TCG opérationnel.

## Exclusion explicite des Translation Blocks one-shot

L'audit de `tb_gen_code()` confirme que QEMU crée un TB temporaire d'une instruction lorsque `phys_pc == -1` et qu'un TB sans page physique associée doit être exécuté au plus une fois, car les lectures I/O peuvent changer. Le futur cache L2 rejettera donc explicitement tout TB sans page(s) RAM validée(s), tout TB MMIO/one-shot et toute page/mapping non revalidable.

## Vérification des données d'unwind et du contexte TB

L'inspection de `tcg_gen_code()` confirme que `INDEX_op_insn_start` fournit les métadonnées requises pour reconstruire `gen_insn_data` et `gen_insn_end_off`, puis le bloc d'unwind de `encode_search()`. Le pont QEMU devra préserver et compter ces opérations, vérifier leur cohérence avec `tb->icount` et laisser QEMU recalculer les offsets hôte. Le contexte de compatibilité devra aussi couvrir `guest_mo`, MTTCG, icount/replay, single-step et plugins ; `TCGOp.nargs` ne sera pas sérialisé comme taille logique, car QEMU peut réutiliser des capacités d'opérations supérieures à leur nombre d'arguments courant.



Le composant `native/phoenixvm-core/src/tcg_ir_cache.rs` contient maintenant un format IR binaire versionné v2 : il sérialise les noms d'opcode et les deux paramètres bruts TCG `param1`/`param2`, des opérandes symboliques, des temporaires/labels bornés et un contexte de compatibilité incluant build QEMU, code invité, CPU/MMU, ABI hôte et CPU features. Les enregistrements sont plafonnés à 2 Mio, protégés par SHA-256 et refusés en cas de corruption ou de structure incohérente.

Le magasin `TcgIrL2Store` ajoute une séparation par VM, des clés de fichier dérivées de l'empreinte complète, lecture/écriture atomiques via worker, invalidation ciblée/VM et éviction LRU sous quotas d'entrées et d'octets. La CI native du commit `7a77b698e23bdc68af13aa8314e6c74f1f0c1cc2` passe `cargo fmt --check` et `cargo test` : 20 tests, 0 échec. Les workflows Android/JVM et APK de ce commit doivent encore être contrôlés séparément.

**Périmètre réel :** la brique native v2 n'est pas raccordée au cœur C de QEMU, au chemin `setjmp_gen_code()/tcg_gen_code()`, au gestionnaire Kotlin ni au runtime packagé. Aucun `TCGContext` n'est reconstruit, aucun TB QEMU n'est restauré, et aucun test froid/chaud inter-processus ni gain de traduction n'est établi. Le magasin L2 natif possède maintenant un verrou noyau `flock` partagé entre les processus Linux/Android et des tests vérifiant la sérialisation et le rejet des liens symboliques. Cette protection couvre le magasin Rust, pas encore les opérations d'un hypothétique adaptateur QEMU C qui n'est pas raccordé. La section 9 reste **PARTIELLE**.

## Dernière avancée section 9 — audit du hook TCG exact de QEMU 11.1.3

L'audit du tag amont `v11.1.3` a maintenant suivi le chemin de création du TB dans les sources : `tb_gen_code()` initialise le contexte et les pages, `setjmp_gen_code()` réinitialise le contexte avec `tcg_func_start()`, appelle `translate_code()`, puis passe les opérations à `tcg_gen_code()` avant l'enregistrement/publication habituels du TB. Les fichiers exacts examinés et les points de code sont consignés dans `docs/TCG_PERSISTENT_CACHE_QEMU_11_1_3.md`.

L'audit a identifié pourquoi une copie brute des structures TCG serait incorrecte : `TCGOp` porte des listes intrusives et données de passe, `TCGTemp` contient des pointeurs et états du processus, les opérations d'appel contiennent des pointeurs de fonctions/helpers et `exit_tb` encode l'adresse du TB courant. Le contrat du patch est donc de reconstruire un graphe IR versionné avec identifiants logiques, de rétablir les temporaires/labels/helpers/TB dans le processus courant, puis de laisser la génération de code, le chaînage et l'invalidation normaux de QEMU fonctionner.

**Nature de cette avancée : audit de source et spécification de l'intégration, pas patch TCG.** Aucun sérialiseur/réhydrateur n'est encore implémenté, aucun runtime QEMU modifié n'a été construit et aucun hit L2 après redémarrage du processus n'est démontré. La section 9 reste **PARTIELLE**.

## Précisions d'intégration TCG — opérations, unwind, temporaires et helpers

L'audit du tag QEMU 11.1.3 a approfondi le contrat de reconstruction. La liste `include/tcg/tcg-opc.h` décrit chaque opcode avec `oargs/iargs/cargs/flags` et QEMU publie les définitions `TCGOpDef` via `tcg_op_defs[]` ; la future adaptation devra valider exactement le nom et le nombre/classe de chaque argument. Les opérations `insn_start` doivent rester présentes : `tcg_gen_code()` les utilise pour reconstruire `gen_insn_data` et `gen_insn_end_off`, qui alimentent `encode_search()` et la restauration d'état invité lors d'une exception. Les offsets hôte sont recalculés, jamais importés.

Les opérandes temporaires sont des pointeurs `TCGTemp *` contenus dans `TCGArg` ; le pont devra réallouer des temporaires du contexte courant via `tcg_temp_new_internal()`, remapper les identifiants et valider les groupes multi-slots. Les globals déjà présents dans `tcg_ctx->temps[0..nb_globals)` doivent être retrouvés puis validés par signature complète (type, kind, index logique, registre ou base/offset mémoire), jamais recréés ni trouvés par nom seul.

Les objets `TCGHelperInfo` générés par `helper-info.c.inc` contiennent un nom et une fonction, ainsi que les signatures d'appel. Aucun lookup générique nom→info n'a été trouvé dans la source amont vérifiée ; les helpers internes manuels peuvent ne pas avoir de nom. Une table de résolution dédiée au binaire/à la cible est requise, avec validation de `nr_in/nr_out/typemask/flags`. Toute opération spéciale, helper absent ou donnée d'unwind non validable devra provoquer un cache miss sûr. Les labels, sorties TB et accès mémoire seront reconstruits par les APIs TCG, tandis que QEMU conserve ses passes habituelles d'optimisation, génération de code hôte, finalisation et invalidation.

Ces points sont des résultats d'audit source et un contrat d'implémentation, pas un patch QEMU déjà compilé ; aucun TB persistant n'est encore réinjecté dans le moteur.

## Dernier contrôle du codec IR v3 — schéma, unwind et labels

Le codec impose désormais un nombre exact de marqueurs `insn_start` correspondant à `instruction_count` et une correspondance univoque entre chaque label et son opération `set_label`. Les tests couvrent les omissions, les définitions mal indexées, les identifiants erronés et le round-trip ordonné des adresses d'instruction. La CI native du commit `d50ac449513549cc1b8d0089b63274a8ed6df95d` passe formatage et tests Rust. Cela fiabilise la représentation IR mais ne constitue toujours pas une reconstitution du `TCGContext` par QEMU.

## Latest Section 9 increment — shared LRU and persistent profile aggregate corrections

The L0 read path now refreshes the recency of the matching resident in L1 for both metadata and artifacts. Without this propagation, an active L0 manifest could remain the oldest L1 item and be evicted by a later artifact insertion even though the shared LRU policy was intended to account for its recent use. The existing cross-kind LRU regression test now exercises this precise sequence.

The profile aggregate reader now parses the nested JSON string stored in the versioned metadata envelope, verifies that the profile compatibility fingerprint matches the envelope key, validates non-negative hit/miss/execution counters and their sum, and protects aggregate counters against integer overflow. putProfile() also rejects hit/miss additions that would overflow. New tests verify aggregate recovery from persisted profiles through a recreated manager and reject invalid overflow counters.

These corrections improve the metadata/artifact cache foundation and its telemetry only. They do not connect the artifact API to QEMU's internal TCG translator and do not demonstrate persistent TCG Translation Block reuse. Section 9 therefore remains PARTIEL until the QEMU 11.1.3 core integration and cold/warm process-relaunch acceptance tests pass.


## Latest implementation increment — persistent translation cache foundation

Section 9 now has a content-addressed cache API for translator-provided, relocation-safe artifacts and metadata. `TranslationCacheKey` includes SHA-256 of the exact guest code bytes, guest-build fingerprint, translator version, host architecture, CPU features and level. The VM-local key also includes the VM ID; the optional global key deliberately excludes it so compatible VMs can reuse an L3 entry without sharing per-VM execution counters.

L0 and L1 hold bounded in-memory metadata/artifact entries through one application-wide `TranslationCacheManager` instance. L2 persists versioned VM-scoped manifests and artifact payloads using temporary files, fsync and atomic rename. Optional L3 (disabled by default) shares only compatible level-3 manifests/artifacts; artifact writes require an explicit translator `relocationSafe=true` declaration, and each envelope validates its format, scope, compatibility fields, payload length and SHA-256 before returning bytes. VM invalidation removes only that VM's L2/local state; global L3 deletion is explicit. Cache hit tiers are counted separately.

Entry and byte budgets are configurable for L0/L1, as is the persistent L2/L3 quota and the maximum artifact size. Metadata and artifact residents now share each L0/L1 tier's entry and byte budget through one combined LRU order; regression tests cover mixed metadata/artifact pressure. Writes trigger periodic size checks and LRU cleanup; reducing the quota prunes immediately. The main UI exposes persistent quota, global L3 opt-in, current size/hit statistics and manual cleanup. Translation profiles/counters are deliberately VM-local and never imported from a global cache. `TranslationEngine` now uses the manager's canonical compatibility fingerprint and provides explicit cached-artifact lookup/store methods for a translator that can produce relocation-safe payloads. For observability, the QEMU TCG backend now polls its existing unstable QMP `x-query-jit` command every five seconds and retains live TB count, generated-code size/capacity, TB flushes, invalidations and direct-jump count in the VM metrics history/UI. This is a genuine view of QEMU's in-memory translation cache, but those counters are not persistent L2/L3 cache hits.

**Important technical boundary:** this cache API is not yet wired to QEMU TCG's opaque live Translation Blocks. QEMU still owns its internal TB code generation, chaining and invalidation; PhoenixVM cannot safely persist/reload those internal TBs without a QEMU/TCG-side integration that exports a versioned, relocation-safe representation and matching invalidation events. The source-build route now has checksum-verified recipes for both Android ARM64-hosted target families: the x86 builder targets `x86_64-softmmu,i386-softmmu`, and `scripts/build-qemu-android-arm64-runtime.sh` targets `aarch64-softmmu`; both pin QEMU 11.1.3 and verify SHA-256 `5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22`. These are source-provenance-controlled build recipes, not yet bit-for-bit reproducible builds, because the host compiler/toolchain and external dependency versions are not pinned. The ARM64 archive is currently only a build/provenance output: the Android app does not yet import or select it, and the pinned DroidVM-Prebuilts runtime remains the shipped default. This increment completes additional cache quota enforcement and adds a checksum-verified ARM64 source-build recipe, but section 9 remains PARTIEL: no translator in the current QEMU runtime calls the artifact API, and QEMU's internal TCG Translation Blocks are not persisted/reloaded across launches. The checklist item “Cache de traduction fonctionnel” is not marked complete.

Automated coverage includes exact code fingerprints, compatibility mismatches, L0/L1/L2 hits, cross-VM L3 reuse, unified mixed metadata/artifact L0/L1 entry and byte budgets, VM-local profile isolation, selective/global invalidation, corrupt artifact rejection, maximum-size metadata payload reopening and automatic/configuration-triggered quota cleanup. Physical performance comparisons remain for the final on-device validation phase.

## Latest implementation increment — CPU topology, runtime affinity and telemetry

Section 8 software work now filters CPU topology to online CPUs intersected with the calling process's effective cpuset (`/proc/self/status`, with cgroup fallbacks). P/E classification uses heterogeneous `cpu_capacity` when available, then maximum frequency; homogeneous or unavailable topology remains `UNKNOWN` rather than being guessed. The scheduler allocates at least one vCPU per active VM, distributes remaining physical slots by VM priority/profile, balances thread assignments and prefers performance or efficiency cores accordingly. At VM startup the selected effective vCPU count is bounded by the user-requested count, current CPU slots across active VMs and thermal policy; the original requested count remains persisted unchanged. Once the guest is running, scheduler refreshes only change thread affinity and do not attempt QMP vCPU hotplug/unplug. Every QMP-discovered vCPU TID is round-robin constrained to the assigned allowed CPU set even when there are more vCPU threads than scheduler slots. An empty/untrustworthy allowed CPU mask yields no scheduler recommendation, preserving the validated runtime fallback rather than forcing one vCPU. The policy maps VM priority into Android/Linux nice values 0..10, avoiding requests for privileged negative nice values on ordinary non-root app processes. It emits the actual permitted CPU IDs.

The runtime resolves QEMU/crosvm process IDs by matching executable identity and the VM-specific QMP/control socket, avoiding accidental targeting of a `su` launcher or another VM's backend. It queries QEMU vCPU TIDs (`query-cpus-fast`) and I/O-thread TIDs (`query-iothreads`). Through the Rust JNI core, it attempts Linux/Android `sched_setaffinity` on backend-process threads; individual vCPU threads receive a single assigned CPU and QMP-identified I/O threads receive the VM's assigned CPU set plus a lower scheduling priority. Other backend threads remain in the VM CPU set but are not guessed to be GPU or audio threads. Priority and affinity failures are counted and logged but never prevent VM startup or continued operation. crosvm processes use the same process-scoped CPU-set containment when their control socket is present, without pretending that QMP per-vCPU IDs are available.

Profiling now distinguishes backend-process CPU/RSS and block I/O (`/proc/<pid>/stat`, `status` and `io`) from system-wide CPU/disk/network metrics, separates system CPU busy time from iowait, records the wall-clock sampling interval and preserves those values in 1/5/30-minute metric windows. Backend CPU percent is null until a valid per-process baseline delta exists, and backend RSS/CPU remain null if the exact QEMU/crosvm PID cannot be resolved; no missing process metric is falsely published as zero. An opt-in Linux QEMU Guest Agent channel is exposed in the creation wizard and persisted VM schema/package configuration; QEMU provides a private UNIX socket chardev connected through `virtio-serial`. The runtime performs `guest-sync-delimited` and `guest-get-cpustats`, computes per-CPU cumulative deltas, and records guest CPU busy time, guest I/O-wait, IRQ/softirq percentage and the sampled guest CPU milliseconds. The guest must install and run a compatible `qemu-guest-agent`; `guest-get-cpustats` is available since QEMU 7.1. Missing, old or unsupported agents, as well as Linux kernels that omit optional `iowait`, `irq`, `softirq` or `steal` counters, leave the complete guest CPU sample unavailable rather than substituting zero. Guest CPU time and guest RAM stay null when no trustworthy guest source exists; they are not reported as zero. Translation/cache timing is surfaced only when profile counters exist. These counters are not a QEMU-internal TCG instrumentation interface, and this application does not claim to observe guest GPU wait or guest CPU stalls without a reliable backend/guest counter. Guest IRQ/softirq are populated only when the optional compatible Linux QEMU Guest Agent responds.

Automated tests cover cpulist parsing, online/cpuset intersection, safe P/E classification, scheduling assignments, nice-value mapping, JNI CPU-mask boundaries, `/proc/stat` parsing, process I/O parsing, QGA Linux counter parsing/deltas and overflow rejection, guest-agent VM config/package round-trip, QEMU virtio-serial command generation, guest metric windows and deterministic thread-placement policy. The native syscall's successful application, OEM cpuset behavior, QEMU thread classification, thermal effectiveness and real performance impact remain deferred physical acceptance. QEMU TCG already owns executable Translation Blocks and its native JIT/chaining behavior; the PhoenixVM-specific persistent cache and tiered-compilation requirements are handled separately under sections 9 and 10.

## Latest implementation increment — guided full-system VM creation

The create flow is now a real seven-page A–G wizard rather than one long form: Architecture, Guest OS/media, Resources, GPU/display, Network, Host/guest sharing, and Optimization/security. It exposes a progress indicator and Previous/Next navigation; the last page changes Next to Create. All seven pages keep their form state while switching, and Android's ADB settings are grouped with guest OS/media. File selection temporarily closes the dialog, copies the selected artifact, and reopens the same wizard page with the updated draft; cancel and import-error paths also return to the previously active page. Validation errors route to the owning page through the pure, unit-tested VmCreationWizardStep policy. Guest/architecture combinations are constrained; creation collects RAM, vCPU, virtual disk size/format, GPU policy, disabled/NAT/TAP networking and port forwards (hostfwd is rejected outside NAT rather than silently omitted), a per-VM shared directory, optional multi-selected transient shared files, and adaptive/performance/battery-saver profiles. ISO, existing disk, kernel, initrd, DTB and Android system/vendor/userdata artifacts are copied from Android's document provider into the VM's private directory using a temporary file, a 64 MiB free-space reserve and same-filesystem atomic rename. Multi-file share imports roll back the entire batch if any item fails. Imported disk containers are checked using non-executing signatures for QCOW2/VHDX/VDI/VMDK; untrusted image metadata is not parsed at creation time. VM creation validates unique name/ID, disk capacity bounds, media paths, shared directories, guest/architecture compatibility, network forwarding and aggregate RAM admission before publishing the record. When requested, the initial cold snapshot is completed before insertion, so the new VM cannot start while its initial disk snapshot is being copied. Store mutations are serialized by one process-wide transaction lock across every VmStore instance. New VM creation, cloning and package import append against the latest store snapshot with uniqueness and RAM admission rechecked under the same lock; process-exit finalization uses an atomic update rather than a stale list rewrite. VM deletion removes the record and managed artifacts under the same lock and only accepts confirmed OFF state. Legacy manager shortcuts that could directly persist STARTING or OFF without the runtime admission/termination contract have been removed. The internal VmEngine API now delegates start directly to VmRuntimeService and does not publish a pre-admission state. Clone, cold snapshot, snapshot restore, package export, ISO/audio/USB edits, optimization and deletion use a process-wide per-VM exclusive lock. Startup admission takes the same lock, rereads the latest stored configuration after waiting for it, validates it and uses that exact snapshot for runtime/USB/TAP setup and launch, then persists STARTING before releasing the lock. No stale pre-lock VM configuration can be launched. The lock is scoped to one VM ID: other VMs can continue lifecycle transitions while a large snapshot or clone is in progress. Collection read/modify/write commits remain briefly serialized by the separate process-wide store mutation lock. Startup RAM admission and the OFF-to-STARTING state transition are one atomic store mutation, serialized against VM creates/imports and other starts. A concurrency regression test verifies that a lifecycle update cannot be lost behind another VM insertion and that duplicate names cannot be committed concurrently. Unit tests cover the pure guest-choice, port-forward, shared-directory and disk-header policies. This remains software-side coverage: guest installation/boot, real bridge/root networking, graphics acceleration, x86 extension availability and hardware/device compatibility are not claimed as accepted.

A requirement is marked complete here only when the repository contains an actual implementation or a deterministic software-side mechanism. Physical acceptance is separated explicitly.

## Latest implementation increment — serialized VM lifecycle and confirmed process shutdown

Lifecycle intents are now FIFO-serialized per VM while separate VMs remain independently schedulable. Process-exit callbacks are routed back through the same per-VM queue and use handle-identity removal, preventing a delayed callback from clearing a newer run. Pause/resume/reboot validate the persisted state, clean stop escalates through bounded graceful/destroy/force-kill stages, and the VM is only persisted as OFF after process termination is confirmed. Missing runtime handles are surfaced as RECOVERY rather than being represented as a successful stop. If an attempted stop cannot confirm termination before its bounded deadline, the running handle retains a recovery-required marker so a later process-exit callback cannot misclassify the VM as cleanly stopped. Unit tests cover per-VM FIFO order, cross-VM concurrency, queue failure recovery, process-termination escalation and pure exit classification. A VM that requires destroy/force escalation is marked RECOVERY, while a process already dead before the stop request remains an unexpected exit. A new start is admitted only from OFF; stale active/error states without a runtime handle are converted to RECOVERY. Startup admission, process registration and service closure share a linearization gate; service destruction marks active guests for recovery immediately and moves bounded process termination/cleanup to background workers, retaining a live handle if termination cannot be confirmed. Backend readiness failures use the same bounded terminator and prohibit fallback if the old process survives. Exit finalization consults the latest handle recovery marker and closed service gate. All process-local VmStore read/modify/write mutations now share a single transaction lock across service, manager and package-import instances. Exit finalization updates the latest stored record atomically instead of rewriting a previously read collection. Physical long-run/restart acceptance remains separate.

## Latest implementation increment — backend-aware QEMU sidecars

QEMU auxiliary runtime processes are now selected by explicit backend capabilities rather than by the `QEMU_TCG` enum value. Both QEMU/KVM and QEMU/TCG can start the verified `virtiofsd` and `swtpm` helpers before the VM process. The crosvm command builder rejects a requested TPM instead of silently omitting it; the runtime can then try the next eligible QEMU fallback. Regression tests cover QEMU/KVM, QEMU/TCG and crosvm capability declarations and the crosvm TPM guard.

## Latest implementation increment — native orchestration policy

The Android orchestrator now crosses a real JNI boundary into the Rust policy core for the initial backend decision. Kotlin still validates the returned backend against the live capability set before accepting it, so a stale or unavailable native decision cannot cause an invalid backend launch.

The native core now exposes:
- backend selection for ARM64 guests across KVM, Gunyah, GenieZone, Halla, AVF and QEMU TCG;
- x86/x86_64 full-system selection as QEMU TCG;
- thermal-aware conservative fallback;
- translation tier classification COLD/WARM/HOT/VERY_HOT.

The native boundary is fail-safe:
- missing native library -> deterministic Kotlin fallback;
- JNI call failure -> QEMU_TCG fallback for the native policy result;
- expert-mode explicit request remains subject to the Android-side availability guard.

## Software work completed

### VM architecture and lifecycle
- Persistent VM model with architecture, guest metadata, machine type, UEFI/NVRAM, storage, RAM, vCPU, GPU, audio, network, sharing, security, profiles and monitoring.
- AUTO, PERFORMANCE, COMPATIBILITY and EXPERT modes.
- Compatibility, Balanced, Performance, Ultra Performance, Battery Saver, Gaming, Office, Server and Development profiles.
- OFF/STARTING/RUNNING/PAUSED/SUSPENDED/STOPPING/ERROR/RECOVERY state model.
- Create, clone, start, clean stop, force stop, pause, resume, reboot, delete, snapshot, restore, export, import, diagnostics and recovery APIs/UI.
- Stable internal VM/HOST/ENGINE API plus event bus foundation.

### CPU / hypervisor / orchestration
- ARM64 QEMU full-system reference backend with KVM when available and TCG fallback.
- Host-aware selection foundation for KVM, Gunyah, GenieZone, Halla, AVF and QEMU TCG.
- Explicit fallback and fail-closed behavior when a backend/runtime is not really available.
- x86_64 and x86/i386 full-system command paths use QEMU system emulators, never Wine/Winlator/user-mode compatibility.
- QEMU TCG MTTCG policy and translation COLD/WARM/HOT/VERY_HOT profiling metadata.
- Persistent translation-cache metadata keyed by VM/guest/translator/host features and cache level.
- Rust native orchestration policy is now invoked from the Android path through JNI, with Kotlin availability guards.
- Translation profiling uses the Rust native tier decision when available, with a deterministic Kotlin fallback.
- Persistent translation profile metadata now records execution count, cache hits/misses and cumulative translation time while remaining compatible with older profile records.
- Multi-VM resource coordination is reevaluated during VM execution with thermal/memory-aware recommendations and QMP balloon recovery when supported. RAM admission conservatively retains commitments for STOPPING, RECOVERY and ERROR VMs until their state is reconciled, because a delayed or failed termination may still own a live process.
- CPU reductions that cannot be hot-applied safely are explicitly marked as next-start recommendations rather than being falsely reported as live changes.

### Memory / scheduling / performance
- P/E-core topology discovery.
- Multi-VM resource admission and global scheduler policy.
- Thermal/memory-aware adaptive performance decisions.
- Balloon-based memory relief through QMP when explicitly enabled.
- Host CPU, memory, thermal, frequency, I/O, network and pressure telemetry.
- 1/5/30 minute metric history.
- Runtime FPS measured from the actual RFB frame stream.
- vCPU host-thread utilization measured from QMP `query-cpus-fast` thread IDs and `/proc` CPU time.
- Persistent translation cache hit rate and average translation time are surfaced when profiles exist.
- Guest CPU time, guest RAM, GPU utilization/memory and AAudio underruns remain explicitly unknown unless a backend/guest exposes trustworthy counters; they are never replaced with fabricated zeros.
- Benchmark model and benchmark engine foundation; sustained-run catalog is defined.

### Graphics / input / audio
- VirtIO GPU software, VirGL and Gfxstream/rutabaga command modes with VNC/RFB display baseline.
- GPU capability reporting distinguishes host Vulkan availability from actually verified VM GPU acceleration; unverified paths are never advertised as accelerated.
- Dynamic resolution/fit behavior and configurable edge swipe, pinch and three-finger gestures.
- VirtIO input devices, keyboard mapping and touch/mouse input path.
- VirtIO sound with Android AAudio backend selection.
- Audio policy supports frequency, channels, buffer size, timer period and mute for QEMU AAudio, with validation and persisted settings.
- crosvm fails closed when the requested audio configuration cannot be guaranteed by its current Android AAudio path.
- Runtime audio monitoring records underrun/overrun/XRUN events only when the backend exposes them in logs; configured buffer latency is reported separately from measured AV synchronization.
- End-to-end AV synchronization remains a physical/guest telemetry acceptance item and is never reported as measured without a trustworthy guest/audio clock path.
- Android Surface/shared-buffer/Vulkan end-to-end presentation remains a physical/device integration item.

### Storage / snapshots / packages
- RAW/qcow2/vhdx/vdi/vmdk creation and qemu-img operations for resize/convert/clone/check/compact.
- Per-VM UEFI/NVRAM and firmware provenance metadata.
- x86_64 Secure Boot is implemented as a verified optional path using the pinned Debian OVMF Secure Boot code + Microsoft-keyed VARS template, with SMM/pflash protection, per-VM Secure Boot NVRAM and fail-closed runtime validation. ARM64 Secure Boot remains unavailable until a verified ARM64 Secure Boot firmware/variable-service runtime is provisioned.
- Cold snapshots with SHA-256 manifest, protection, retention and restore.
- VM package import/export with manifest and SHA-256 verification.
- Optional Ed25519 package signature verification when signature/public-key sidecars are supplied.
- Versioned VM schema migration and configuration backup.

### Network / sharing / peripherals
- NAT and root/TAP networking, port forwarding, per-VM deterministic MAC, hostname/DNS/MTU policy.
- VirtIO network controller and explicit network validation.
- VirtIO-FS path when verified virtiofsd is present; 9p/VirtFS fallback otherwise.
- XHCI virtual USB controller.
- Per-VM host USB passthrough configuration with Android UsbManager permission lifecycle, explicit device matching, hub rejection, bus/address resolution, direct-node/root access gating and QEMU `usb-host` launch translation.
- USB passthrough is fail-closed: permission alone is not treated as sufficient host access, and non-QEMU/crosvm backends are not allowed to silently receive host USB devices.
- Explicit host USB passthrough policy model that never grants devices globally by default.
- TPM 2.0 orchestration through verified swtpm when present.

### Guests / compatibility / ecosystem
- Linux ARM64 remains the primary supported target in software.
- Windows ARM64/x86_64 guest specification and validation model.
- Android ARM64 guest specification for system/userdata/vendor/kernel/bootloader/ADB/display/network.
- BSD, legacy x86 and custom guest catalog.
- Device profile database structure for Qualcomm, MediaTek, Exynos, Tensor and other ARM64 hosts.

### Security / diagnostics / licensing
- Canonical path and allowed-root checks.
- Runtime critical binary/firmware SHA-256 verification.
- Security audit findings model.
- Ed25519 signature verification utility.
- Per-VM structured logging ERROR/WARN/INFO/DEBUG/TRACE.
- Diagnostic ZIP export and developer reports.
- Third-party component provenance/license tracking documentation.
- No software path substitutes a compatibility layer for a VMM.

### UI / developer mode / CI
- VM dashboard with lifecycle controls, ISO import, snapshots, clone, optimization advice, package import/export, diagnostics and developer reporting.
- Configurable gesture settings UI.
- Developer benchmark/metrics report foundation.
- GitHub CI for Rust format/tests and Android unit tests/lint/build.
- Additional quality/security workflow and license tracking checks.

## Explicitly not marked complete yet — requires runtime availability or physical acceptance

1. Actual KVM/Gunyah/GenieZone/Halla/AVF execution on representative Android devices.
2. Actual QEMU x86_64/i386 Android runtime build and execution on the target phone; the pinned DroidVM-Prebuilts bundle does not ship these binaries.
3. virtiofsd runtime build/start on Android.
4. swtpm runtime build/start on Android.
5. Full crosvm display/input/audio/network device bridges across vendor hypervisors.
6. Direct Android Surface + Vulkan/shared-buffer presentation end-to-end.
7. USB host passthrough including Android permission lifecycle.
8. Windows ARM64/x86 installation and VirtIO driver acceptance.
9. Android guest boot acceptance including kernel/bootloader/system/vendor/userdata/ADB.
10. Actual audio latency/AV-sync acceptance.
11. Representative CPU/GPU/I/O benchmark runs and +20% claims.
12. Limbo/QEMU/crosvm/DroidVM baseline comparison under equivalent settings.
13. 30/60 minute sustained thermal tests, 100 boot/reboot/restore stability tests and screen-off/background/interruption tests.
14. Fuzzing/path-traversal/sandbox/security tests on physical Android builds.
15. Complete Snapdragon/Dimensity/Exynos/Tensor device matrix with measured results.
16. Release-grade signing/update/rollback testing.

## Technical limits

The application must not report hardware acceleration, native Surface presentation, TPM, VirtIO-FS, x86 support or performance gains unless the corresponding real runtime component has started successfully. Software-side capability detection is not proof of physical availability.

The translation-cache foundation remains policy/metadata plus an artifact API around QEMU TCG; no translator in the shipped runtime currently consumes those artifact APIs. QEMU TCG remains authoritative for executable Translation Blocks, and PhoenixVM does not claim that those blocks are persisted or reloaded across launches.

## Validation boundary

GitHub CI can validate source, unit tests, formatting, APK assembly and packaging. It cannot prove vendor hypervisor access, GPU driver behavior, audio behavior, guest boot, thermal sustainability or Android permission behavior without a real device.

## Mise à jour du 27 novembre 2026 — adaptateur QEMU et lancement Android

### Avancées réelles

- Le format TCG IR natif est en version 3 et applique un schéma fermé par opcode ; les formes inconnues sont rejetées.
- Le magasin L2 natif utilise un verrou noyau inter-processus Linux/Android par flock, sur un fichier permanent ouvert avec O_NOFOLLOW.
- Un pont C ABI expose le L2 par VM, le préchargement worker vers un L1 résident non bloquant, le lookup candidat par contexte et les fonctions de construction/lecture des blocs IR.
- Un patch QEMU 11.1.3 est présent dans patches/qemu/11.1.3. Le hook intervient dans setjmp_gen_code() après tcg_func_start() ; à un hit il reconstruit les opérations dans le TCGContext courant et laisse QEMU générer normalement le code hôte. En cas de miss/rejet, il réinitialise le contexte et rappelle le traducteur upstream. Les TB one-shot/page-less/MMIO, icount/single-step, breakpoints et contextes plugin sont exclus.
- Sur le commit 1fea0440b8a8bc46ad0ae1a9116fc3e61cebd408, la CI a compilé QEMU 11.1.3 pour x86_64-softmmu et aarch64-softmmu, puis vérifié les symboles phoenixvm_tcg_cache_start/try_restore/store. La CI Android a passé le build natif Android, les tests unitaires, le lint, les contrôles de scripts/SBOM et l'assemblage APK debug.
- L'application ne transmet les variables de cache que si le runtime contient simultanément le marqueur patché et la bibliothèque native. Le répertoire L2 est séparé par VM. Les builders de runtime Android ajoutent ces artefacts et leurs sommes SHA-256.

### Résultat froid/chaud : échec actuel

Le test utilise un BIOS x86 déterministe, arrête complètement QEMU via isa-debug-exit puis relance un nouveau processus avec le même L2. Le run sur le commit 1fea044 a compilé les deux binaires et vérifié les symboles, mais le test a échoué à l'étape froide : aucun message de bloc mis en cache, aucun fichier IR persistant et seul le fichier .tcg-ir-l2.lock était présent. Il ne démontre donc ni hit après relance, ni gain de traduction. Le patch C contient désormais des marqueurs explicites d'initialisation et d'entrée des hooks ; la nouvelle exécution de diagnostic est en cours et son résultat n'est pas encore établi.

La compilation Android et l'APK réussis ne prouvent pas que le runtime modifié a été installé sur appareil. Le runtime ARM64 produit par le builder n'est pas encore sélectionné par l'installation standard de l'application ; la bibliothèque Android du CI ne remplace pas à elle seule le QEMU système préinstallé. Les validations de code invité modifié, contexte CPU/MMU, build QEMU/features hôte, corruption, isolation réelle entre processus, MTTCG, relances répétées et mesures de traduction restent à effectuer. Les compteurs de hits L2 propres à l'adaptateur ne sont pas encore exposés via QMP/UI.

**Décision :** la section 9 reste **PARTIELLE** et la PR #14 reste en brouillon. Aucun taux de hits persistants ni gain de performance n'est revendiqué avant réussite du test froid/chaud, de la matrice d'incompatibilité et des mesures répétées.