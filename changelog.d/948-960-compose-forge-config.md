### Fixed (2026-10-03 — #948 / #960: shipped Docker config pinned a stale release and probed routes that do not exist)
- **#948 — the compose file silently ran the previous release.** `aether/docker/docker-compose.yml` defaulted
  the node image to `${AETHER_VERSION:-1.0.0-rc3}`, so a reader on an rc4 checkout following the documented
  pull path ran rc3 binaries with no warning. `AETHER_VERSION` is now **required** (`${AETHER_VERSION:?…}`,
  the style already used for `AETHER_CLUSTER_SECRET`): an unset variable fails the command, and an
  unpublished tag fails with a registry 404 instead of substituting another release. A shipped default
  cannot be derived here — Maven does not own this file and the release flow only runs `versions:set` on
  poms. `DockerConfig.DEFAULT_IMAGE` (used by the generated Kubernetes manifest and Docker config) carried
  the same hard-coded `1.0.0-rc3`; it now reads `BuildInfo.version()` from the jar manifest (`dev` on a
  bare classpath, visibly not a release). A dead `AETHER_IMAGE="…:1.0.0-rc3-candidate"` in
  `tests/cloud/deploy-cloud.sh` (no consumer, a release behind `aether-cloud.toml`) was removed.
- **#948 — docs named paths that do not exist.** `cd docker` and `docker build -f docker/…` were written as if
  `docker/` were at the repository root; it is `aether/docker/`, which is also the build context. Corrected in
  `docker-deployment.md` and `current-docker-setup.md`, and `AETHER_VERSION` is now documented where the pull
  path is described. `versioning-and-compatibility.md` no longer states a literal current rc.
- **#960 — the Forge image healthcheck probed `/api/metrics`, which Forge does not serve** (it serves
  `/api/metrics/history`), so the container was reported unhealthy forever. It now probes
  `/api/forge/status` (constant-time, unauthenticated). Same class, found by checking every healthcheck
  target against the route tables: the `scaling-test` compose files, `wait-healthy.sh` and `soak-test.js`
  probed `/api/health`, and the generated Kubernetes probes, Docker compose healthcheck and
  Docker/Local `status.sh` and `script/demo-cluster.sh` probed bare `/health` — none exist on a node, which
  serves `/health/live` and `/health/ready`. The generated Docker healthcheck also used `curl`, which the node
  image does not install; it now uses `wget`.
