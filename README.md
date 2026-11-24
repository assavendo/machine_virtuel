# PhoenixVM / Machine Virtuelle Android

Projet de machine virtuelle complète sur Android, basé sur le cahier des charges du 23 novembre 2026.

## Positionnement

Ce projet vise une vraie VM complète (CPU, RAM, firmware, disque, GPU, réseau, audio, input), et non un simple lanceur d'applications Windows via Wine.

## État actuel

- Fondations Android + orchestrateur VM : en construction.
- Cache de traduction persistant : prévu et intégré au modèle de données.
- Core natif Rust : intégré au build CI.
- Backends hardware/QEMU : architecture d'abstraction prête, runtime complet à intégrer progressivement.
- APK : construit automatiquement par GitHub Actions.

Le projet ne prétend pas encore fournir un backend VM complet tant que QEMU/crosvm et leurs runtimes Android ne sont pas réellement embarqués et validés sur appareil.

## Référence

Le cahier des charges complet utilisé comme source de vérité est conservé hors dépôt dans le contexte du projet et doit rester le référentiel fonctionnel.
