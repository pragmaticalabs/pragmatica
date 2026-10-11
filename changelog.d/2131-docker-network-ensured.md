### Fixed (#2131: a docker bootstrap failed with "network not found" on a host without the network)

- `aether cluster bootstrap` with a `docker` source now ensures the Docker network its containers join (default `aether-network`, overridable with
  `AETHER_DOCKER_NETWORK` or `network_name`): reused when present, created before the first container when absent. Previously nothing created it
  and nothing said it had to exist, so a first bootstrap failed on the first core.
- Ownership is a docker label: a network bootstrap creates carries `aether.created-by-cluster=<cluster>`, and only an OWNED network is written to the
  cleanup ledger (`DockerNetwork`). A network that already existed with no label, or another cluster's, is reused and never recorded. One carrying this
  cluster's own label (an earlier run left it in place) is owned again, so a re-run does not leak it.
- `aether cluster destroy` removes exactly the recorded network name (never the provider's current one), only if it still carries the cluster's label and
  no container is attached, after the containers are gone. A network with members is left in place and reported. A user-provided network is never removed.
- Known limit: two clusters sharing one network leave it in place when the creating cluster is destroyed first; leaking is the safe direction.
- [verified: `DockerNetworkTest`, `DockerNetworkBootstrapTest`]
