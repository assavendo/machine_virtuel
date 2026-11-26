# Section 9 — conception du cache TCG persistant (cible QEMU 11.1.3)

## Décision d’ingénierie

**État : non implémenté dans le cœur TCG ; section 9 reste PARTIELLE.**

La cible actuellement épinglée pour le runtime x86/x86_64 est QEMU **11.1.3**, source archive :

- Version : `11.1.3`
- SHA-256 : `5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22`
- Script : `scripts/build-qemu-android-x86-runtime.sh`
- Cibles : `x86_64-softmmu,i386-softmmu`

Le script actuel compile QEMU depuis l’archive amont et n’applique aucun patch TCG PhoenixVM. Il ne faut donc pas présenter le runtime stock comme capable de restaurer des Translation Blocks d’un processus précédent.

## Audit du code PhoenixVM existant

| Élément | Rôle effectivement vérifié | Limite |
|---|---|---|
| `TranslationCacheManager.kt` | Stockage atomique de profils/métadonnées, clés par VM, fingerprint, version traducteur, architecture et features hôte ; L0/L1/L2 et L3 opt-in | Les valeurs persistées ne sont pas du code TCG exécutable ni une représentation TCG réinjectable |
| `QemuCommandBuilder.kt` | Active TCG/MTTCG et calcule `tb-size` suivant le profil et la RAM | `tb-size` règle la capacité du cache en mémoire du processus ; il ne définit pas un cache disque |
| `VmRuntimeService.kt` | Lit les agrégats de profil pour la télémétrie | Aucun mécanisme vérifié ne restaure des TB persistés dans le moteur |
| `native/phoenixvm-core/src/translation.rs` | Politique de classification COLD/WARM/HOT/VERY_HOT | Aucun chemin de génération/réutilisation de code TCG |
| `docs/IMPLEMENTATION_STATUS.md` | Déclare la section 9 PARTIELLE | Cet état doit rester PARTIEL jusqu’aux tests TCG de relance décrits ci-dessous |

Les tests du gestionnaire Kotlin ajoutés dans ce changement portent uniquement sur la persistance des métadonnées L2. Ils ne doivent jamais être comptabilisés comme preuve de réutilisation des traductions TCG.

## Ce que QEMU 11.1.3 fournit — et ne fournit pas dans l’interface amont vérifiée

L’option TCG `tb-size` dimensionne le cache des Translation Blocks en mémoire. `thread=multi` configure les threads TCG ; aucun des deux ne persiste les blocs entre processus.

Les plugins TCG exposent des événements de traduction et d’exécution à des plugins d’observation. Leur API est passive : elle ne permet pas au plugin de modifier l’état de la machine ni de remplacer le chemin de traduction TCG. Un plugin peut donc profiler les blocs, mais ne peut pas, à lui seul, restaurer une traduction dans le cache interne.

QEMU 11.1.3 expose aussi la commande QMP instable \`x-query-jit\` (déclarée dans \`qapi/machine.json\` sous \`CONFIG_TCG\`). PhoenixVM interroge cette commande et parse les données réellement publiées par \`accel/tcg/tcg-stats.c\` : nombre de TB présents, taille/capacité du code généré, compteurs de flush/invalidation et nombre de sauts directs. Ces compteurs sont utiles comme baseline et comme preuve que TCG tourne ; ils décrivent exclusivement l'état du processus courant. Ils ne donnent ni le nombre de hits L2 persistants, ni les blocs restaurés depuis un processus précédent. Les tests du parseur utilisent les libellés produits par QEMU 11.1.3 et tolèrent l'absence de champs optionnels.

La réutilisation inter-processus exige une évolution du cœur QEMU. Il est interdit d’injecter une option non prise en charge telle que `-accel tcg,persistent-cache=...` dans le runtime stock.

Références à revalider lors de toute mise à niveau QEMU :

- [QEMU 11.1.3 — publication stable](https://www.qemu.org/download/)
- [Invocation QEMU — paramètres TCG, `tb-size` et `thread`](https://www.qemu.org/docs/master/system/invocation.html)
- [TCG Translator Internals](https://www.qemu.org/docs/master/devel/tcg.html)
- [TCG Plugins](https://qemu.readthedocs.io/en/stable-11.1/about/emulation.html)
- Sources de la cible : `accel/tcg/translate-all.c`, `include/exec/translation-block.h`, `tcg/tcg.c` dans le tag amont `v11.1.3`.

## Architecture requise pour une vraie persistance L2

### 1. Format de cache versionné et non exécutable

Le premier prototype doit persister une représentation canonique et validable de la traduction (par exemple une forme intermédiaire TCG sérialisée), plutôt que de réinjecter directement des octets de code hôte. Un fichier de cache ne doit jamais être exécuté ou mappé comme du code sans validation complète.

Chaque entrée doit contenir au minimum :

- magic, version de format, taille totale et checksum ;
- identifiant exact du build QEMU et version/ABI du sérialiseur TCG ;
- architecture hôte, ABI, endianness, taille de page et masque des fonctionnalités CPU nécessaires ;
- architecture invitée, modèle CPU et contexte d’exécution requis ;
- PC invité, drapeaux TB, contexte de MMU/privilège, flags/cflags pertinents ;
- empreinte des octets de code invité couverts par le TB et identité des pages physiques invitées ;
- liste d’opérations IR encodées avec des identifiants stables, tailles et limites vérifiables — jamais de pointeurs bruts du processus ;
- métadonnées de recherche/désassemblage nécessaires à la reconstruction du TB.

Les opcodes, les nombres d’opérations, les temporaires, les longueurs, les indices et toutes les références doivent être bornés et validés à la lecture. Une incompatibilité, une corruption ou une version inconnue doit provoquer un *cache miss* sûr, jamais une exécution partielle.

### 2. Clé de compatibilité

Une clé complète doit inclure au moins :

1. VM propriétaire et architecture invitée ;
2. fingerprint du contenu du code invité réellement traduit ;
3. contexte CPU/TB/MMU qui influe sur la traduction ;
4. build QEMU exact, cible softmmu, version TCG et version du format de cache ;
5. architecture/ABI/features CPU hôte et version du backend de génération de code.

Le simple identifiant de build de l’OS invité n’est pas suffisant : un binaire ou une page de code peut changer à l’intérieur du même build. La clé doit être liée aux octets du code concerné et au contexte du TB.

### 3. Intégration dans le cœur TCG

Le patch QEMU doit introduire des points d’intégration explicites :

- recherche L2 avant le décodage/traduction standard, après résolution et validation du contexte de code invité ;
- validation de l’empreinte des pages de code et des paramètres TB ;
- reconstruction des opérations IR dans le contexte TCG courant, puis génération normale du code hôte et création des structures TB internes ;
- fallback immédiat sur la traduction amont en cas de miss, d’échec de décodage, d’incompatibilité ou d’erreur I/O ;
- persistance après génération réussie, hors du chemin critique du vCPU ;
- statistiques TCG fiables exposées à QMP : hits L2, misses, entrées rejetées, invalidations, octets lus/écrits, temps de désérialisation et temps de traduction évité/consommé.

Il faut conserver le contrôle normal de QEMU sur le code généré, les sauts chaînés, les tables de recherche et les structures d’invalidation. Le format IR doit être reconstruit et réémis dans le buffer TCG du nouveau processus ; il ne doit pas réintroduire des pointeurs, adresses ASLR ou patches de saut de l’ancien processus.

### 4. Cohérence et sécurité

- Isoler les fichiers L2 par VM ; ne pas partager le cache par défaut.
- Vérifier le contenu du code invité à chaque réutilisation candidate ; invalider les entrées dont le code, le mapping ou le contexte ne correspond plus.
- Traiter explicitement l’auto-modification de code, les écritures DMA, les remappings MMU, les changements de privilège, les breakpoints, le single-step, les plugins et les modes icount/replay.
- Ne jamais effectuer de lecture disque, verrou long ou fsync sur le thread vCPU.
- Écrire via fichier temporaire, checksum, fsync et rename atomique ; gérer les processus concurrents avec une stratégie de verrouillage documentée.
- Imposer quota L2 par VM, politique LRU, limite par entrée, nettoyage et suppression sélective.
- Au démarrage, rejeter un cache dont le build QEMU, l’architecture hôte, les CPU features ou le format ne sont pas identiques.
- Le cache étant dérivé du contenu invité, ne jamais le considérer comme une source de confiance.

### 5. MTTCG

Les accès concurrents par vCPU doivent être sûrs. Le cache ne doit pas imposer un verrou global dans le chemin d’exécution. Le prototype devra tester les lectures simultanées, les écritures atomiques, l’arrêt brutal pendant la persistance et deux processus PhoenixVM n’utilisant pas le même répertoire VM.

## Plan de validation obligatoire

Tous les tests doivent être exécutés avec le binaire construit depuis la source vérifiée QEMU 11.1.3 et le patch PhoenixVM correspondant. Une exécution avec un QEMU hôte d’une autre version ne valide pas le runtime Android ciblé.

| Cas | Procédure | Résultat exigé |
|---|---|---|
| Baseline froide | Effacer le L2, démarrer le même guest/workload, relever compteurs et temps TCG | 0 hit L2 ; temps de traduction de référence |
| Relance chaude | Arrêter complètement le processus QEMU, conserver le L2, relancer le même guest/workload | Hits L2 non nuls et baisse mesurée du nombre de traductions/durée de traduction |
| Relance sans changement | Répéter plusieurs arrêts et relances | Hits reproductibles ; aucun résultat fondé sur une seule exécution |
| Code modifié | Modifier un bloc ou une page de code invité, puis relancer | Ancienne entrée rejetée ; traduction correcte du nouveau code |
| Mapping/contexte modifié | Modifier le mapping, les flags CPU/MMU ou le privilège | Entrée incompatible rejetée |
| Build/CPU incompatible | Changer build QEMU, format, ABI ou features hôte | 0 réutilisation incompatible, fallback normal |
| Corruption | Tronquer ou altérer un fichier du cache | Rejet sûr ; aucune panne du guest |
| Isolation | Deux VM avec code ou configuration proches | Aucune fuite ou collision inter-VM |
| MTTCG | Plusieurs vCPU, invalidations et écritures concurrentes | Pas de corruption, deadlock ou course observée |
| Quota/nettoyage | Saturer le quota et relancer | LRU/limites respectés, données VM préservées |

Le rapport doit conserver : commit QEMU et PhoenixVM, hash du binaire, modèle du téléphone, SoC, Android, kernel, CPU features, configuration VM, empreinte guest, durée de boot/workload, nombre de TB compilés, hits/misses L2, temps de traduction et température. Comparer plusieurs runs froids et chauds, mêmes images, mêmes paramètres et conditions thermiques proches.

**Critère d’acceptation :** la relance doit démontrer des hits L2 réels fournis par le cœur TCG, confirmer la compatibilité de chaque entrée réutilisée, et réduire de façon répétable le nombre/temps de traduction par rapport au baseline. Une hausse du taux de hit des métadonnées Kotlin ou une baisse du temps de boot isolée ne suffit pas.

## Décision de statut

À ce stade, seuls le cache de métadonnées PhoenixVM et le dimensionnement du cache TCG en mémoire sont présents. Aucun code TCG/IR n’est réinjecté après redémarrage et aucun benchmark de réutilisation inter-processus n’a été exécuté. La section 9 reste donc **PARTIELLE** jusqu’à l’implémentation du patch QEMU 11.1.3, à la compilation du runtime Android modifié et à la réussite de tous les critères de validation ci-dessus.
