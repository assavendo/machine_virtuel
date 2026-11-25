# Protocole de benchmark (cahier §28, 29, 30, 56, 57)

Aucune affirmation de performance n'est publiée sans fichier de résultat conforme.

## Fichier de résultat

JSON décrit en tête de `tools/bench_compare.py` : appareil (modèle, SoC, Android, noyau), backend, configuration VM, température initiale, charge, unité, sens de la mesure, durée soutenue, liste des `runs`.

## Conditions de mesure

- même appareil, même charge, même unité pour la baseline et le candidat ;
- au moins 5 exécutions par configuration ;
- température initiale notée ; appareil au repos, batterie et mode d'énergie identiques ;
- charges soutenues : 30 minutes minimum (60 si possible) pour exposer le throttling ;
- baselines à comparer séparément : Limbo/QEMU de référence, QEMU optimisé, crosvm matériel, PhoenixVM.

## Règle du « +20 % »

`tools/bench_compare.py BASELINE.json CANDIDATE.json` renvoie `PROVEN` seulement si : médiane du candidat meilleure d'au moins 20 %, écart supérieur au bruit entre exécutions, durée soutenue respectée. Sinon `NOT PROVEN` avec la raison, ou `INVALID`.

## Anti-régression (§57)

Pour chaque optimisation : résultat avant, résultat après, mémoire, température, stabilité, compatibilité, retour arrière. Une optimisation qui améliore un indicateur mais dégrade un critère critique est refusée.

## État

Aucun résultat mesuré n'existe à ce jour : ce protocole est prêt, les mesures exigent un téléphone.
