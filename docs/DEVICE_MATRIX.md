# Matrice de compatibilité appareils (cahier §32, 50)

Fichier de données : `docs/device-matrix/devices.json`, contrôlé par `tools/device_matrix_check.py`.

Une capacité (kvm, gunyah, geniezone, halla, avf, qemu_tcg, virtio_gpu_accel, vulkan, usb_passthrough) est `untested` par défaut. Elle ne peut passer à `works` ou `fails` que si l'entrée contient au moins une preuve (`evidence` : fichier de résultat et date).

Familles prévues : Snapdragon, Dimensity, Exynos, Tensor, autres ARM64. La matrice est vide : aucun appareil n'a encore été mesuré.
