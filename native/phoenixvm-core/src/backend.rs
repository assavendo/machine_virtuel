#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Backend {
    Kvm,
    Gunyah,
    GenieZone,
    Halla,
    Avf,
    QemuTcg,
}

#[derive(Debug, Clone, Copy)]
pub struct HostFeatures {
    pub kvm: bool,
    pub gunyah: bool,
    pub genie_zone: bool,
    pub halla: bool,
    pub avf: bool,
    pub thermal_status: i32,
}

pub fn select_backend(arm64_guest: bool, host: HostFeatures) -> Backend {
    if !arm64_guest {
        return Backend::QemuTcg;
    }
    if host.thermal_status >= 5 {
        return if host.kvm {
            Backend::Kvm
        } else {
            Backend::QemuTcg
        };
    }
    if host.kvm {
        Backend::Kvm
    } else if host.gunyah {
        Backend::Gunyah
    } else if host.genie_zone {
        Backend::GenieZone
    } else if host.halla {
        Backend::Halla
    } else if host.avf {
        Backend::Avf
    } else {
        Backend::QemuTcg
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn x86_is_always_tcg() {
        assert_eq!(
            select_backend(
                false,
                HostFeatures {
                    kvm: true,
                    gunyah: true,
                    genie_zone: true,
                    halla: true,
                    avf: true,
                    thermal_status: 0
                }
            ),
            Backend::QemuTcg
        );
    }

    #[test]
    fn arm64_prefers_kvm() {
        assert_eq!(
            select_backend(
                true,
                HostFeatures {
                    kvm: true,
                    gunyah: true,
                    genie_zone: true,
                    halla: true,
                    avf: true,
                    thermal_status: 0
                }
            ),
            Backend::Kvm
        );
    }
}
