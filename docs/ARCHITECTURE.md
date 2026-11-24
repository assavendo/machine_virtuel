# PhoenixVM architecture

The target is a real full-system VM. Android is the control plane; the engine owns guest CPU, memory, firmware, storage, network, graphics, audio and input.

Layers:
1. Android control plane
2. VM orchestrator
3. Rust native engine
4. hardware/software backends
5. VirtIO device layer

Preferred hardware backends are KVM, Gunyah, GenieZone, Halla and AVF/pKVM when actually exposed by the host. QEMU TCG/MTTCG remains the software full-system fallback.

FEX/Box64/Box86 are optional translation components, never substitutes for a full VMM.

The application must never report a VM as running when no compatible backend runtime has actually executed. The current foundation therefore blocks false-positive starts.
