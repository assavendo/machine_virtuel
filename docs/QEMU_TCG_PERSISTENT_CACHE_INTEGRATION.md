# PhoenixVM — intégration du cache persistant dans QEMU TCG 11.1.3

**Statut : spécification d'intégration — non implémentée et non validée.**  
**Branche de travail :** `work/qemu-tcg-persistent-cache-integration`  
**Base :** `work/persistent-translation-cache`  
**Version QEMU visée :** `v11.1.3`  
**Archive source attendue :** `qemu-11.1.3.tar.xz`  
**SHA-256 :** `5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22`

## 1. Décision d'architecture

Le cache applicatif L0/L1/L2/L3 de PhoenixVM constitue une couche de stockage et de validation, mais il ne reçoit actuellement aucun artefact du traducteur TCG livré. Le paramétrage QEMU `tb-size` règle uniquement la capacité du cache de TB en mémoire du processus.

La première intégration à expérimenter est un **cache persistant de représentation intermédiaire TCG (IR)**, et non une sauvegarde brute du code machine hôte :

1. Sur un défaut de cache, le traducteur invité produit l'IR TCG comme aujourd'hui.
2. QEMU sérialise une représentation canonique et versionnée de l'IR, uniquement si chaque référence de l'entrée est sérialisable et validable.
3. Au lancement suivant, QEMU vérifie la clé de compatibilité et les octets invités concernés, puis reconstruit les objets TCG dans le contexte courant.
4. QEMU exécute de nouveau ses passes d'optimisation, d'allocation de registres, de résolution des relocations et d'émission native.
5. Si une seule validation échoue, QEMU abandonne l'entrée et reprend le chemin normal de traduction. Le cache ne doit jamais être requis pour démarrer une VM.

Ce choix vise à éviter de restaurer des adresses de code hôte, des chaînes de sauts directes ou des pointeurs provenant d'un autre processus. Il ne promet pas une accélération avant mesure : l'IR évite potentiellement le décodage invité, mais les passes TCG et l'émission du code hôte restent nécessaires.

## 2. Points d'insertion dans QEMU 11.1.3

Le point de contrôle central se trouve dans `accel/tcg/translate-all.c`, dans `setjmp_gen_code()` :

- après `tcg_func_start(tcg_ctx)`, le contexte TCG de ce bloc est initialisé ;
- avant `cs->cc->tcg_ops->translate_code(...)`, QEMU peut tenter de reconstruire l'IR ;
- après le chemin de traduction normal, l'IR peut être sérialisé ;
- dans les deux cas, le chemin se poursuit avec `tcg_gen_code(tcg_ctx, tb, pc)`, afin de régénérer le code hôte et ses métadonnées dans le processus actif.

Ce branchement doit être implémenté dans l'arbre source QEMU épinglé, pas dans le seul code Kotlin/Rust de PhoenixVM. Le second point d'intégration est `accel/tcg/tcg-all.c` : une propriété TCG explicite (par exemple `persistent-cache-dir`) permettrait de configurer le stockage. La propriété doit être désactivée par défaut et documentée par QEMU.

Sources upstream auditées :

- [QEMU 11.1.3 — accel/tcg/translate-all.c](https://github.com/qemu/qemu/blob/v11.1.3/accel/tcg/translate-all.c)
- [QEMU 11.1.3 — accel/tcg/tcg-all.c](https://github.com/qemu/qemu/blob/v11.1.3/accel/tcg/tcg-all.c)
- [QEMU 11.1.3 — include/tcg/tcg.h](https://github.com/qemu/qemu/blob/v11.1.3/include/tcg/tcg.h)
- [QEMU 11.1.3 — tcg/tcg.c](https://github.com/qemu/qemu/blob/v11.1.3/tcg/tcg.c)
- [QEMU 11.1.3 — include/exec/translation-block.h](https://github.com/qemu/qemu/blob/v11.1.3/include/exec/translation-block.h)

## 3. Format IR : références reconstruites, jamais copiées comme pointeurs

Le format disque doit être indépendant des adresses virtuelles du processus. Il doit représenter les références au moyen d'identifiants locaux à l'entrée :

- **Temporaires TCG :** indices de temporaires. Les temporaires globaux sont rapprochés des globaux du contexte courant ; les temporaires locaux sont recréés, puis leurs références croisées sont remappées.
- **Labels :** identifiants d'entrée, puis recréation des labels et reconstruction des listes d'utilisations/relocations.
- **Opérations TCG :** opcode, type, paramètres immédiats, durée et arguments encodés selon leur nature. Chaque opcode et sa disposition d'arguments doivent être validés contre la version du schéma.
- **Appels auxiliaires :** identité symbolique et signature validées par une table de fonctions auxiliaires autorisées. Les adresses de fonction et les pointeurs `TCGHelperInfo` ne sont jamais écrits dans le fichier.
- **Métadonnées du TB :** `pc`, `cs_base`, `flags`, `cflags`, taille du code invité, nombre d'instructions et informations d'instruction nécessaires à la reconstruction du TB.

Les structures TCG contiennent notamment des pointeurs entre `TCGTemp`, des arguments `TCGOp` pouvant désigner des temporaires/labels, et des pointeurs de fonction et d'informations auxiliaires pour les appels. Copier les structures brutes ou leurs tableaux d'arguments n'est donc pas une sérialisation valide.

Toute opération contenant une référence inconnue, une fonction auxiliaire non résolue, une structure spécifique à un plugin ou une donnée opaque doit faire échouer la mise en cache de ce TB seulement. Le chemin de traduction natif reste le repli obligatoire. La première livraison doit commencer par une liste blanche étroite et testée d'opcodes ; l'extension de la liste se fait progressivement.

## 4. Clé de compatibilité et périmètre initial

La clé doit être composée au minimum de :

- version du format et version exacte de QEMU (11.1.3), plus identifiant du patch/build PhoenixVM ;
- cible TCG, architecture hôte, caractéristiques CPU hôte pertinentes et configuration de compilation affectant le TCG ;
- architecture/CPU modèle invité, endianness, mode système, état de traduction et ordre mémoire ;
- `pc`, `cs_base`, `flags` et `cflags` du bloc ;
- empreinte SHA-256 des octets de code invité effectivement consommés pour ce TB ;
- identifiant de machine virtuelle et empreinte de la configuration invitée qui influence la traduction.

**Périmètre initial conservateur :** TCG system-mode, blocs issus de RAM ordinaire, sans instrumentation de débogage/plugin, sans mode single-step/icount, sans accès MMIO au code, et seulement les TB dont les octets invités ont été lus et validés sans ambiguïté. Les blocs qui traversent une limite de page, ont un état particulier non sérialisé ou ne peuvent pas prouver leur compatibilité sont des misses obligatoires au premier incrément.

L'empreinte exacte doit être calculée à partir des octets invités réellement lus par le mécanisme de fetch de code, sous les protections/verrous applicables. Il ne faut pas supposer que les pages RAM sont contiguës dans l'espace hôte. Si les octets ne peuvent pas être récupérés sans effet de bord ou sans ambiguïté, on ne consulte pas l'entrée persistante.

Le cache L3 inter-VM demeure désactivé pour ce premier incrément. Il ne sera autorisé qu'après validation des clés sans identité VM, de l'isolation des données, de la compatibilité complète et de tests multi-VM.

## 5. Invalidation et intégrité

La persistance ne modifie pas les règles d'invalidation des TB actifs de QEMU. Les listes de pages, les jump lists, le verrouillage des sauts, les invalidations de code auto-modifiant et les chaînes directes restent gérés par QEMU. Une entrée IR persistée est un candidat à la reconstruction, jamais un TB déjà actif.

Un cache hit est accepté uniquement après validation intégrale du conteneur : version, longueur, bornes, compatibilité, références, empreinte du code invité et SHA-256 du payload. Les fichiers incomplets, trop grands, corrompus, issus d'une autre version/build ou présentant une référence non résolue sont rejetés sans arrêter le démarrage.

L'écriture doit suivre le modèle déjà employé par PhoenixVM : fichier temporaire dans le même système de fichiers, écriture complète, synchronisation, renommage atomique et quotas LRU. Une écriture interrompue ne doit jamais remplacer un objet valide. L'invalidation d'une VM supprime ses données locales ; la suppression globale reste une opération explicite.

## 6. Observabilité requise

Les métriques de cache applicatif existantes ne doivent pas être réutilisées comme preuve de hits TCG. Le moteur QEMU modifié doit exposer des compteurs dédiés et stables :

- `persistent_ir_lookup`, `persistent_ir_hit`, `persistent_ir_miss` ;
- `persistent_ir_rejected_compatibility`, `persistent_ir_rejected_corrupt`, `persistent_ir_unsupported` ;
- `persistent_ir_load_bytes`, `persistent_ir_save_bytes` ;
- temps cumulé de recherche, validation, désérialisation, traduction invitée évitée, optimisation et émission native.

Un endpoint QMP documenté (par exemple `query-tcg-persistent-cache`) est préférable au seul `x-query-jit`, qui est une commande expérimentale et renseigne principalement le cache TB natif en mémoire. Les compteurs de persistance doivent être réinitialisés ou identifiés par lancement afin de distinguer clairement les cold starts des warm starts.

## 7. Plan de tests et critères de passage

| Étape | Essai obligatoire | Critère de réussite |
|---|---|---|
| Build | QEMU 11.1.3, cibles ARM64 et x86 softmmu retenues | Compilation sans avertissement bloquant ; binaire et manifeste identifiés |
| Cold run | Démarrage d'un invité représentatif avec cache vide | Misses attendus, génération et écriture d'IR valide |
| Warm run | Arrêt complet du processus puis relance avec le même invité | Hits persistants réels, sans réutilisation de mémoire du processus précédent |
| Compatibilité | Modifier CPU, flags, build QEMU, code invité ou format | Miss, jamais de replay incompatible |
| Intégrité | Tronquer, corrompre et modifier le payload | Rejet, fallback de traduction et démarrage fonctionnel |
| Code auto-modifiant | Invité qui écrit puis exécute une page de code | Résultat invité identique au cache désactivé |
| Frontières | TB cross-page, MMIO, debug, single-step, plugins | Fallback sûr pour chaque cas non pris en charge |
| Multi-vCPU | MTTCG avec accès concurrents au même cache | Aucune corruption, deadlock ou course de publication |
| Performance | Plusieurs cold/warm runs sur le même appareil et la même image | Baisse statistiquement crédible du temps de traduction et du coût total de relance, sans régression de correction |

Les tests doivent comparer cache désactivé, premier démarrage (miss) et relances (hits). Pour chaque configuration, conserver les mêmes binaire, image disque, firmware, vCPU, RAM, mode TCG, état thermique initial et scénario invité. Rapporter médiane et dispersion de plusieurs répétitions, pas une seule mesure. Les temps de recherche/désérialisation doivent être déduits des temps de traduction évités ; un meilleur compteur de hits sans baisse du coût total n'est pas une réussite de performance.

## 8. Intégration Android/PhoenixVM après validation QEMU

Une fois la propriété et le QMP disponibles dans le binaire patché :

1. le runtime Android vérifie l'identité du binaire QEMU et ses capacités avant d'activer le cache ;
2. il crée un répertoire privé par VM et transmet sa localisation à QEMU via la propriété TCG ;
3. il relie les compteurs QMP aux métriques VM sous des noms distincts des hits applicatifs ;
4. il expose le quota, l'effacement et l'état du cache dans l'interface ;
5. tout binaire stock non patché conserve exactement le comportement actuel, sans recevoir une propriété inconnue.

Le builder ARM64 existant doit appliquer un patch QEMU versionné et vérifier son empreinte avant la configuration/compilation. Le patch doit être testé séparément sur les cibles `aarch64-softmmu`, `x86_64-softmmu` et `i386-softmmu` requises. L'archive de runtime produite doit inclure la version, le hash du patch, la configuration et le manifeste SHA-256.

## 9. État à ce jour et blocages de validation

L'audit de la branche confirme que PhoenixVM possède déjà le cache d'artefacts/métadonnées L0–L3, les empreintes, le contrôle d'intégrité, les quotas et les statistiques de profil. En revanche, le dépôt ne contient pas encore le patch source QEMU décrit ci-dessus, le traducteur TCG ne consomme pas ces artefacts et le binaire Android généré à partir du script ARM64 n'est pas encore intégré/sélectionné par l'application.

Aucun build QEMU modifié, test de restauration IR entre processus ou benchmark cold/warm n'a été exécuté dans le cadre de cet incrément. L'environnement de travail courant n'offre pas de checkout/build Android ARM64 du code QEMU ; les sources upstream ont été auditées à distance, ce qui permet de définir les points d'insertion, pas de prétendre à une validation compilée.

**Conclusion : section 9 reste PARTIELLE.** Elle ne peut être marquée TERMINÉE qu'après le patch TCG, les tests de correction/compatibilité ci-dessus, un binaire QEMU 11.1.3 réellement consommé par PhoenixVM et la preuve mesurée que les relances font des hits persistants et réduisent le coût de traduction et de démarrage.
