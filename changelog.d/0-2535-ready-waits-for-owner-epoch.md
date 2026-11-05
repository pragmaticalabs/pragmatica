### Fixed
- `/health/ready` no longer reports UP before the node's DHT owner-epoch record is applied (#2535). Until then the node refused DHT writes while readiness said it was ready. Readiness now stays DOWN until the record is applied. Known residual: a write can still be refused when the owner epoch is re-minted while the write is in flight (the cluster-incarnation mint window, #2654).
