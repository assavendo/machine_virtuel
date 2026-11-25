# Modèle de menaces (cahier §33, 49)

Frontière de sécurité : le guest ne doit pas atteindre l'hôte Android ni les autres VMs.

| Menace | Contrôle présent dans le dépôt | Vérification restante |
|---|---|---|
| Package VM malveillant (traversée, bombe ZIP, doublons) | `unzipSafe`, limites d'entrées et de taille, `tools/vmpack_lint.py` | fuzzing en continu |
| Package falsifié | SHA-256, Ed25519, empreinte de clé approuvée | rotation de clés à définir |
| Binaire ou firmware remplacé | SHA-256 du runtime et du firmware | signature de release |
| Accès fichiers hôte excessif | chemins canoniques, racines autorisées, partages explicites | revue d'isolation sur appareil |
| USB hôte exposé au guest | passthrough explicite par VM, fail-closed | test de permissions réel |
| Réseau entre VMs | NAT par VM, MAC par VM | test d'isolation multi-VM |
| Évasion VMM | backends fail-closed, pas de root implicite | test d'évasion et sandbox sur appareil |
| Chaîne d'approvisionnement | SBOM, `FILE_COMPONENT_MAP.json` | revue humaine des entrées « inferred » |

Les lignes « vérification restante » ne sont pas closes : elles exigent du fuzzing dédié ou un appareil.
