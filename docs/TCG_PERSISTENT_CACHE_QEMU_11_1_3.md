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

## Audit source détaillé du point d’intégration TCG (tag amont v11.1.3)

L’inspection du code exact du tag `v11.1.3` confirme le chemin d’exécution suivant :

1. `accel/tcg/translate-all.c:tb_gen_code()` alloue le `TranslationBlock`, établit son contexte (`pc`, `cs_base`, `flags`, `cflags`), résout la première page physique du code et prend les verrous de page nécessaires.
2. `setjmp_gen_code()` appelle ensuite `tcg_func_start()`, installe le CPU courant dans le contexte TCG et appelle le traducteur de l’architecture via `translate_code()`.
3. Le même chemin appelle finalement `tcg_gen_code()`, qui optimise les opérations TCG, réalise les passes de reachability/liveness, alloue les registres et émet le code hôte.
4. Après génération, `tb_gen_code()` encode les informations de restauration d’état, initialise les sauts, insère le TB dans les structures de recherche et le publie via le mécanisme normal de liaison aux pages.

Les emplacements ont été vérifiés dans les fichiers du tag QEMU 11.1.3 :
- [`accel/tcg/translate-all.c`](https://github.com/qemu/qemu/blob/v11.1.3/accel/tcg/translate-all.c) — `setjmp_gen_code()` et `tb_gen_code()`.
- [`tcg/tcg.c`](https://github.com/qemu/qemu/blob/v11.1.3/tcg/tcg.c) — `tcg_func_start()`, allocation des TCGOp, construction des appels helpers et `tcg_gen_code()`.
- [`include/tcg/tcg.h`](https://github.com/qemu/qemu/blob/v11.1.3/include/tcg/tcg.h) — structures `TCGLabel`, `TCGTemp`, `TCGOp`, `TCGContext` et définitions d’opérations.
- [`tcg/tcg-op.c`](https://github.com/qemu/qemu/blob/v11.1.3/tcg/tcg-op.c) — émission des terminators `exit_tb` et `goto_tb`.

### Conséquences vérifiées pour le format persistant

- `tcg_func_start()` réinitialise les pools temporaires, remet `nb_temps` à `nb_globals`, vide les tables de constantes et réinitialise les listes d’opérations et de labels. Une restauration doit donc reconstruire son état **après** cette remise à zéro et **avant** les passes de `tcg_gen_code()`.
- `TCGOp` n’est pas un enregistrement sérialisable tel quel : il contient une entrée de liste intrusive, des champs de vie/registre propres aux passes et un tableau flexible d’arguments `TCGArg`. Il faut encoder les opérations et leurs références logiques, pas recopier les octets de la structure.
- `TCGTemp` contient des valeurs et pointeurs d’exécution, notamment `mem_base`, `name` et `state_ptr`. Les pointeurs, l’état d’allocation et l’état des passes ne doivent jamais être importés d’un ancien processus. Les temporaires doivent être recréés et leurs identifiants remappés.
- `tcg_gen_callN()` place explicitement l’adresse de la fonction helper et celle de son `TCGHelperInfo` dans les arguments de l’opération. Ces adresses ne sont pas persistantes. Le format doit référencer un helper par identifiant symbolique stable, le résoudre dans le processus courant, et rejeter tout helper non reconnu.
- `tcg_gen_exit_tb()` encode une adresse liée au `TranslationBlock` courant dans l’opération `exit_tb`. Cette terminaison doit être régénérée à l’aide de l’API TCG pour le TB nouvellement alloué ; la valeur ou le pointeur du cache ne doit pas être réutilisé.
- Les labels et leurs listes de branches/relocations référencent des objets TCG de l’exécution courante. Leur graphe doit être recréé avec une table de correspondance locale.
- Les champs `tc.ptr`, les offsets de sauts, les liens directs, les pages verrouillées, les index de recherche et l’insertion dans les arbres/QHT appartiennent au cycle de vie normal du TB. Ils doivent être reconstruits par QEMU, jamais importés comme un `TranslationBlock` brut.

### Contrat du premier patch TCG

Le premier patch devra donc intercepter le chemin dans `setjmp_gen_code()`, à l’intérieur du cycle normal de création du TB. Après `tcg_func_start()`, il devra tenter de reconstruire un graphe TCG versionné ; en cas de miss, d’opcode ou de helper non pris en charge, d’incompatibilité ou de corruption, il devra exécuter le `translate_code()` amont sans changement. Dans les deux cas, l’émission du code hôte restera effectuée par le `tcg_gen_code()` normal, puis QEMU continuera son initialisation et sa publication habituelles du TB.

La sérialisation doit inclure les compteurs et métadonnées de traduction nécessaires, dont `tb->size`, `tb->icount` et les informations d’instruction utilisées pour `restore_state_to_opc()`. Les terminaisons TB, les labels, les helpers et les temporaires seront reconstruits à partir d’identifiants de fil stables. Les fichiers L2 seront préchargés ou lus par un worker vers un index mémoire borné ; une lecture disque, un `fsync` ou un verrou long ne sera jamais effectué par le thread vCPU. Les écritures seront envoyées à un worker et ne devront jamais ralentir le chemin critique.

Cette inspection **ne constitue pas une implémentation du sérialiseur**. Elle précise le contrat nécessaire pour qu’un patch réel soit sûr et évite explicitement trois fausses solutions : sérialiser les structures C brutes, restaurer du code machine hôte depuis un autre processus, ou réutiliser les pointeurs de helpers/TB de l’ancien processus.

## Brique native v3 — schéma IR fermé et magasin L2, sans raccord TCG

Un premier composant natif indépendant de QEMU est maintenant codé dans `native/phoenixvm-core/src/tcg_ir_cache.rs` :

- Format binaire déterministe `PVTCGIR`, version 3, avec longueur bornée à 2 Mio, SHA-256 du contenu et rejet des versions, longueurs, références et enregistrements invalides.
- Identité de compatibilité qui transporte le build QEMU exact, architecture et modèle CPU invités, build invité, architecture/ABI/endianness hôte, taille de page, liste canonique des CPU features, PC/base CS, flags TB/cflags, MMU index, pages physiques, empreinte des octets de code et digest du contexte spécifique TB.
- Schéma fermé par liste blanche : `insn_start`, `mov`, `add`, `sub`, `and`, `or`, `xor`, `mul`, `br`, `set_label`, `goto_tb` et `exit_tb`. Seules les formes scalaires I32/I64 expressément décrites sont admises ; toute opération, helper ou forme d'opérande non prise en charge est rejetée. Les trois mots de données d'`insn_start` sont obligatoires. `exit_tb` stocke un indice abstrait (0, 1 ou 3), jamais une ancienne adresse TB ; `goto_tb` accepte uniquement les indices 0 ou 1.
- Les globals sont référencés par index numérique dans `TCGContext.temps`, jamais par nom uniquement. Le contrat impose que `tb_context_sha256` couvre la disposition complète de tous les globals/fixed temps ; le futur adaptateur QEMU devra calculer et comparer ce digest (index, types, kind, registre ou base/offset, flags et relations indirectes). Cette comparaison n'est pas encore exécutée dans le cœur QEMU.
- Enregistrement des temporaires borné et vérification des groupes multi-emplacements ; index de labels et références d'opérandes validés avant acceptation.
- `TcgIrBlock::write_atomic/read_file` fournit l'écriture temporaire, `sync_all`, renommage atomique, synchronisation du répertoire parent et lecture plafonnée.
- `TcgIrL2Store` isole les entrées par VM sous un répertoire dérivé d'un SHA-256 de l'identifiant VM, et par identité de compatibilité pour le nom de fichier. Il vérifie le contexte complet à la relecture, transforme la corruption/version inconnue en cache miss sûr, propose invalidation ciblée/VM, quotas d'entrées et d'octets et éviction LRU par date d'accès. Les lectures/écritures disque sont réservées à un worker, jamais au thread vCPU.
- Le magasin utilise un `Mutex` pour les threads d'une instance et un verrou noyau `flock(LOCK_EX)` sur `.tcg-ir-l2.lock` pour sérialiser les opérations entre processus Linux/Android. Le descripteur reste ouvert pendant l'opération, le verrou est libéré à la fermeture du processus, le fichier de verrouillage n'est jamais supprimé et son ouverture utilise `O_NOFOLLOW`. Les chemins lecture, écriture, invalidation, statistiques et nettoyage prennent le verrou avant tout accès aux entrées ; les opérations restent réservées à un worker, jamais au thread vCPU. Les tests vérifient l'attente entre deux ouvertures indépendantes et le rejet d'un lien symbolique.

La CI native sur le commit `7a77b698e23bdc68af13aa8314e6c74f1f0c1cc2` a réussi : `cargo fmt --check` et `cargo test`, avec **20 tests réussis, 0 échec**. Les tests couvrent l'aller-retour du format, les champs param1/param2, helpers symboliques, empreintes et incompatibilités, corruption/troncature, groupes de temporaires, persistance atomique, isolation VM, invalidation sélective et quotas LRU.

**Limite déterminante :** ce codec et ce magasin constituent une référence native PhoenixVM, pas un patch du cœur QEMU. Aucun pont C ne convertit/reconstruit encore les `TCGOp`, temporaires, globals, labels, helpers, états d'unwind ou terminaisons TB dans le `TCGContext` courant. Le code QEMU ne les appelle pas ; aucun binaire QEMU 11.1.3 modifié n'est construit et aucun hit L2 de TCG après redémarrage n'est démontré. Le magasin L2 natif n'est pas encore raccordé au gestionnaire Kotlin existant. La lecture du magasin reste une API de worker et n'est pas une lecture sur le chemin vCPU.

## Audit supplémentaire des symboles TCG, helpers et données d’unwind (tag v11.1.3)

### Contrat des opérations

`include/tcg/tcg-opc.h` est la liste canonique des opérations génériques : chaque `DEF(name, oargs, iargs, cargs, flags)` décrit le nombre d'outputs, d'inputs, de constantes et les flags. Le contrat exposé par `TCGOpDef` dans `include/tcg/tcg.h` ajoute `nb_args`. L'adaptateur doit retrouver une opération par son nom stable dans `tcg_op_defs[]`, puis vérifier que le nombre et la classe de tous ses arguments correspondent exactement à cette définition. Un nom d'opcode inconnu, non présent ou spécial non pris en charge doit être un cache miss ; le codec Rust ne suffit pas à autoriser l'émission d'un opcode arbitraire.

La version 3 conserve les octets bruts `param1` et `param2`, mais le schéma fermé ne permet que les valeurs validées pour les opérations scalaires déclarées. Toute extension doit documenter le sens des paramètres pour l'opcode QEMU exact et ajouter des tests de fidélité avant d'être admise.

### Schéma typé des arguments d'opération

`TCGOpDef` fournit les nombres d'outputs, d'inputs et de constantes, mais ne décrit pas à lui seul le type sémantique de chaque constante. Certaines constantes sont des immédiats, d'autres des références de labels ou des valeurs qui contiennent des adresses propres au processus (par exemple l'ancienne valeur TB d'un `exit_tb`). Les opérations spéciales ont aussi des formes variables (`call`) ou des données de contexte particulières (`qemu_ld/qemu_st`, `goto_tb`, plugins).

Le patch doit donc inclure une table blanche d'encodage/décodage par opcode qui classe chaque argument logique en temporaire, global, immédiat, label ou helper symbolique, puis valide sa cardinalité et son type contre `TCGOpDef`. La table doit traiter spécifiquement `call`, `br`, `brcond`, `set_label`, `goto_tb`, `exit_tb`, `goto_ptr`, `insn_start`, `qemu_ld/qemu_st` et les opérations plugin. Pour le premier prototype, tout opcode sans encodeur/décodeur explicitement testé sera écarté du cache ; le traducteur amont reste le fallback systématique.

### Données de restauration d'état invité

QEMU conserve les opérations `INDEX_op_insn_start` pendant l'optimisation précisément parce qu'elles alimentent l'unwind. Dans `tcg_gen_code()`, elles remplissent `TCGContext.gen_insn_data` et `gen_insn_end_off` au fur et à mesure de la nouvelle génération du code hôte ; `translate-all.c` encode ensuite ces données avec `encode_search()`, et `cpu_restore_state_from_tb()` les utilise via `restore_state_to_opc()` en cas d'exception/fault.

Conséquence : le cache doit conserver chaque opération `insn_start` et ses constantes, dans l'ordre exact, et contrôler que leur nombre correspond à `tb->icount`. Il ne doit jamais restaurer les offsets hôte de l'ancien processus ; ceux-ci sont recalculés par `tcg_gen_code()`. Le bloc Rust contient `instruction_count`, mais l'adaptateur QEMU devra encore vérifier la cohérence du nombre de marqueurs `insn_start` et rejeter tout bloc incomplet.

### Temporaires et globals

Les arguments TCG qui désignent un temporaire sont des pointeurs `TCGTemp *` encodés par `temp_arg()`/relus par `arg_temp()`. Ces valeurs ne peuvent pas être conservées. Après `tcg_func_start()`, le reconstructeur doit créer de nouveaux temporaires dans le contexte courant via le chemin interne `tcg_temp_new_internal(type, kind)` et construire une table de correspondance `id IR -> TCGTemp * courant`. Les groupes multi-emplacements (notamment I128) doivent être alloués comme des groupes cohérents ; les temporaires constants doivent être normalisés en immédiats ou recréés avec `tcg_constant_internal()`.

Les globals/fixed temps existent déjà dans `tcg_ctx->temps[0..nb_globals)` après l'initialisation du contexte. Ils ne doivent pas être alloués à nouveau. Le nom seul n'est pas une validation suffisante : l'adaptateur doit comparer l'index logique et la signature attendue (type/base_type, kind, registre fixé ou base/offset mémoire, flags indirects) à la disposition courante. Le digest de contexte doit inclure le layout complet des globals propres à la cible.

### Helpers

`TCGHelperInfo` expose un nom symbolique, la fonction courante, les flags, `typemask`, `nr_in`, `nr_out` et le layout d'appel. Pour la cible x86, `target/i386/tcg/translate.c` inclut `helper-info.c.inc` avec `HELPER_H "helper.h"`; le traducteur ARM inclut le même générateur avec `tcg/helper-defs.h`. Les macros du fichier génèrent les objets `helper_info_<nom>` avec `.func` et `.name`.

**Limite vérifiée :** la source amont consultée n'expose pas de registre générique nom -> `TCGHelperInfo *`. De plus, les helpers internes de TCG définis manuellement dans `tcg/tcg.c` (par exemple les `info_helper_*_mmu`) ne déclarent pas forcément de nom. Le patch QEMU doit donc fournir un registre explicite propre au binaire/à la cible, ou une table générée à partir des définitions helpers. À la relecture, le nom doit être résolu dans le processus courant et le reconstructeur doit comparer `nr_in`, `nr_out`, `typemask`, flags et le layout d'arguments avant de régénérer l'appel avec les pointeurs courants. Un helper absent, interne non enregistré ou dont la signature diverge impose un cache miss sûr ; le pointeur `func` et le pointeur `TCGHelperInfo *` de l'ancien processus ne sont jamais importés.

### Opérations à reconstruire spécialement

Les appels (`call`), labels/branches (`set_label`, `br`, `brcond`), sorties/chaînage (`exit_tb`, `goto_tb`, `goto_ptr`), marqueurs de plugins et accès mémoire `qemu_ld/qemu_st` ne doivent pas être restaurés par une simple copie générique. `exit_tb` doit être régénéré avec le `TranslationBlock *` nouvellement alloué ; les labels et leurs listes d'usages doivent être créés dans le contexte courant ; les chemins `qemu_ld/st` doivent régénérer leurs données de slow-path et labels de finalisation. Pour le premier prototype, le mode plugin, les marqueurs non compris, les opérations spéciales non prises en charge, icount/replay et les modes d'exécution dont le contexte ne peut pas être validé doivent forcer un cache miss.

Même après import d'IR, QEMU doit continuer à appliquer normalement `tcg_optimize()`, reachability/liveness, allocation de registres, génération code hôte, unwind, finalisation ldst/pool, insertion/lien TB et invalidation page. Le cache évite le décodage invité uniquement pour les formes entièrement prises en charge ; il ne restaure pas le code machine hôte ni les structures internes d'un ancien TB.

## Précisions complémentaires du contexte et des métadonnées TB

Le contexte de compatibilité doit aussi représenter l'ordre mémoire invité `TCGContext.guest_mo`, le mode de traduction (notamment MTTCG), les flags de comptage/icount, le mode single-step/replay, l'état des plugins TCG et les autres options qui changent la sémantique ou la forme des opérations. Ces valeurs doivent être prises depuis le contexte effectif du TB, pas déduites du seul profil utilisateur. Si une option active n'a pas de représentation complète dans la clé et dans l'IR, l'import doit être refusé.

Le tableau des opérations impose une distinction entre :
- les arguments sortie/entrée décrivant des temporaires ou globals ;
- les arguments constants décrits par `TCGOpDef.nb_cargs` ;
- les opérations à nombre d'arguments variable ou qui possèdent des pointeurs auxiliaires, comme `call` ;
- les opérations spéciales qui imposent une reconstruction dédiée.

Le champ `TCGOp.nargs` n'est pas une taille de format persistante fiable : QEMU peut réutiliser une structure dans `free_ops` avec une capacité supérieure au nombre d'arguments logiques. Le format doit encoder le nombre logique déterminé par `TCGOpDef` ou par le descripteur spécialisé ; le reconstructeur alloue ensuite la taille locale requise. `life`, `output_pref`, les liens intrusifs et les données de passes sont recalculés par les passes normales.

Les `TEMP_CONST` ne doivent pas être restaurés comme adresses temporaires : leur valeur doit être encodée comme immédiat ou reconstruite avec `tcg_constant_internal()`. Pour les globals/fixed temps, la clé doit inclure un digest canonique du layout de `tcg_ctx->temps[0..nb_globals)` (nom, type, kind, registre fixe ou base/offset mémoire, relations indirectes) ; la validation par nom seul n'est pas suffisante. Les temporaires locaux seront remappés dans le contexte courant, leur ordre d'allocation n'a pas à reproduire les anciens pointeurs, mais chaque référence IR doit viser le nouveau temporaire correspondant.

`INDEX_op_insn_start` est une opération dédiée dont les constantes constituent les données de restauration invitées. Sa présence et son ordre sont obligatoires pour régénérer `gen_insn_data`, `gen_insn_end_off`, `tb->icount` et les données compactées par `encode_search()`. Si un bloc ne contient pas exactement le nombre attendu de marqueurs, ou si un marqueur est manquant/non pris en charge, il doit être rejeté.

Enfin, les opérations `qemu_ld/qemu_st` dépendent du contexte `guest_mo`, des chemins ldst différés et de la finalisation des labels de slow path. Elles ne sont pas équivalentes à des opérations arithmétiques ordinaires, même si leurs formes figurent dans `tcg_op_defs[]`.

## Tests structurels du codec IR v3

Le codec vérifie maintenant que le nombre d'opérations `insn_start` est exactement égal à `instruction_count`, et que chaque entrée de table de labels pointe vers un unique `set_label` portant le même identifiant. Les tests de régression vérifient le rejet d'un compteur incomplet, d'un index de définition erroné et d'une opération de définition absente, ainsi que l'aller-retour ordonné des adresses d'instruction et du label. Le commit natif `d50ac449513549cc1b8d0089b63274a8ed6df95d` passe `cargo fmt --check` et `cargo test`; cette validation porte toujours sur le codec/store Rust, sans intégration C au TCG.

### Exclusion des TB one-shot et des pages non persistantes

`tb_gen_code()` traite `phys_pc == -1` comme un TB temporaire limité à une instruction. QEMU indique qu'un TB sans page physique associée doit être exécuté au plus une fois, car une lecture ultérieure de mémoire I/O peut renvoyer une valeur différente. Ces TB ne sont donc jamais sérialisés, jamais recherchés dans L2 et jamais restaurés.

La condition d'éligibilité au cache doit exiger une ou deux pages de code RAM réellement résolues et verrouillables, des mappings/permissions compatibles et une relecture des octets de code identiques. Les fenêtres MMIO, les TB sans page physique, les pages non lisibles de façon fiable, et toute traduction dont l'identité de page ne peut être revalidée sont des cache misses obligatoires.

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
