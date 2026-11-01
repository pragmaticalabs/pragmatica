### Added
- The M-1 harness (#2512) gains the S6 arms (one slice deployed: every worker, one per community, a cores-only control, and undeploy), an echo-slice content pre-flight, and a CONFIG_SET pre-flight (`m1.classes=s6a,s6b,s6c,s6d,preflight-config`). `echo-slice` is now a declared test dependency of `aether/ember`. Test-only; excluded from CI by the `Benchmark` tag.
