### Fixed (#1341: preserve real consensus history in the restart probe)
- The slow-rejoin probe now opts into per-node consensus persistence before first boot, retaining its participation markers and unchanged no-replacement/scale-up assertions.
- The node snapshot adapter decodes the phase envelope written by Git-backed persistence. A saved checkpoint no longer silently becomes absent history because its header was passed to the Base64 decoder.
- Consensus shutdown drains accepted application work before saving its final checkpoint and rejects new submissions once stopping begins. A concurrent state-machine callback can no longer leave a checkpoint behind the applied state. The restart fixture commits and observes an explicit history marker before stopping.
