# SBOM : association fichier -> composant/licence

`THIRD_PARTY/FILE_COMPONENT_MAP.json` relie chacun des 91 fichiers de `THIRD_PARTY/SBOM.json` à son ou ses composants amont.

- `declared` : composant et licence viennent de `DroidVM-license.yaml`.
- `inferred` : lien ou licence déduits du projet amont, **non vérifiés** contre le build réel. Une personne doit les confirmer.

`scripts/validate-file-component-map.py` vérifie que chaque fichier a exactement une règle, qu'aucune règle n'est morte, que chaque composant est connu, et signale les entrées inférées. Tant que `status` n'est pas `reviewed`, la release n'est pas déclarée complète côté supply-chain (`releaseReady` reste `false` dans le SBOM).

Points à revoir avant release : contenu réel de `initramfs.img`, implémentation `libintl`, licence exacte de `efi-virtio.rom`, provenance de `libvulkan_freedreno.so`.
