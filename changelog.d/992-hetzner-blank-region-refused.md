### Fixed (2026-09-29 — #992: region requiredness was enforced only at cluster init, so a Hetzner config from any other source reached the wire with location="")
- **A Hetzner provision with no region was sent as `"location":""`.** `cluster init` demands an explicit region, but a
  config written by hand, templated or produced by an older CLI reached the provider unchecked. Hetzner is the one
  provider whose placement is not already a required credential: AWS, GCP and Azure fail a blank region at factory
  construction. Aether's residency rule is that it never picks a jurisdiction on the operator's behalf, and for
  Hetzner that rule rested on `init` alone.
  `HetznerComputeProvider.createFrom` now refuses a provision whose resolved region is blank, with a `ProvisionFailed`
  naming `[cloud.compute] region`, before any Hetzner call.
  The check sits at server creation rather than in the factory on purpose: cleanup (`cluster destroy`, handle-based
  cleanup) and discovery build the provider without a region and never place a server.
  [mechanism: guard at the only create-server entry; pinned by
  `HetznerComputeProviderTest.provision_blankRegion_failsBeforeCreateServer`, which asserts no create-server request
  was sent; red on the pre-fix code]
