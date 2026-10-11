### Fixed (#2131: a docker bootstrap failed with "network not found" on a host without the network)

- `aether cluster bootstrap` with a `docker` source now ensures the Docker network its containers join (default `aether-network`, overridable with
  `AETHER_DOCKER_NETWORK` or `network_name`): reused when present, created before the first container when absent. Previously nothing created it
  and nothing said it had to exist, so a first bootstrap failed on the first core.
- Ownership is recorded: a network bootstrap CREATED is written to the cleanup ledger as `DockerNetwork`; one that already existed is not. `aether cluster
  destroy` removes a recorded network only after the containers (nodes are removed first) and only if no container is still attached; a network with
  members is left in place and reported. A network you provided is never in the ledger, so it is never removed.
- [verified: `DockerNetworkTest`, `DockerNetworkBootstrapTest`]
