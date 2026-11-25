# Format de package VM (.vmxpack, format_version 2)

Référence : `VmPackageManager.kt` (cahier §37). Le package est une archive ZIP accompagnée de deux fichiers voisins `<package>.sig` et `<package>.pub` (Ed25519).

## Contenu

| Entrée | Obligatoire | Description |
|---|---|---|
| `manifest.json` | oui (≤ 1 MiB) | Métadonnées, tailles et SHA-256 |
| `disk/disk.<ext>` | oui, un seul | Image disque |
| `firmware/nvram.fd` | si `nvram_present` | NVRAM UEFI |
| `boot/<champ>-<nom>` | si déclaré | kernel, initrd, dtb, system, vendor, userdata Android |
| `snapshots/<nom>/…` | optionnel | Snapshots à froid avec leur `manifest.json` |

## Champs du manifeste

`format_version` (1 ou 2), `vm_id`, `name`, `architecture`, `disk_format`, `disk_size_gib`, `disk_size_bytes`, `disk_file_name`, `sha256_disk`, `nvram_present`, `nvram_size_bytes`, `sha256_nvram`, `boot_artifacts[]` (`field`, `package_path`, `size_bytes`, `sha256`), `signing_public_key_sha256` (v2), `vm_config`.

## Règles de rejet (reprises par `tools/vmpack_lint.py`)

Chemins absolus, `\`, NUL, lettres de lecteur, `.`/`..`, entrées dupliquées, plus de 50 000 entrées, manifeste absent ou trop gros, `vm_id` hors `[A-Za-z0-9-]{1,64}`, disque absent ou multiple, taille ou SHA-256 incohérents, champ de boot inconnu, snapshot d'une autre VM, paire `.sig`/`.pub` incomplète, empreinte de clé différente de celle du manifeste.

## Ce que le linter ne fait pas

Il ne vérifie pas la signature Ed25519 ni l'approbation de clé sur l'appareil (`PackageTrustStore`) : ces contrôles restent dans l'import Android.

Usage : `python3 tools/vmpack_lint.py vm.vmxpack [--json]`.
