### Fixed (#1341: preserve real consensus history in the restart probe)
- The slow-rejoin probe now opts into per-node consensus persistence before first boot, retaining its participation markers and unchanged no-replacement/scale-up assertions.
- The node snapshot adapter decodes the phase envelope written by Git-backed persistence. A saved checkpoint no longer silently becomes absent history because its header was passed to the Base64 decoder.
