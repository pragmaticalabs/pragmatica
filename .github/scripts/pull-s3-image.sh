#!/usr/bin/env bash
# Pre-pulls the MinIO image the @Tag("S3") tests pin by digest (#3629): a registry blip on cgr.dev during
# Testcontainers' own pull errored a whole run. The digest is read from the test constants (the one place it lives);
# both test classes must carry the same value, so the workflow and the tests cannot drift.
# Usage: pull-s3-image.sh [tries] [backoff-seconds]   (env S3_IMAGE_SOURCES overrides the files, for the control run)
set -euo pipefail
tries="${1:-3}"
backoff="${2:-10}"
sources="${S3_IMAGE_SOURCES:-integrations/storage/src/test/java/org/pragmatica/storage/RemoteTierS3IntegrationTest.java aether/node/src/test/java/org/pragmatica/aether/node/StorageFactoryS3SharedTierTest.java}"
pattern='MINIO_DIGEST = "\K[^"]+'
image=""
for file in $sources; do
  found="$(grep -oP "$pattern" "$file" || true)"
  if [ -z "$found" ]; then
    echo "::error::no MINIO_DIGEST constant in $file"; exit 1
  fi
  if [ -n "$image" ] && [ "$image" != "$found" ]; then
    echo "::error::MINIO_DIGEST differs between S3 test classes: $image vs $found ($file)"; exit 1
  fi
  image="$found"
done
for attempt in $(seq 1 "$tries"); do
  if docker pull "$image"; then
    echo "pulled S3 test image $image (attempt $attempt/$tries)"; exit 0
  fi
  echo "pull of $image failed (attempt $attempt/$tries)"
  [ "$attempt" -lt "$tries" ] && sleep $((backoff * attempt))
done
echo "::error::could not pull S3 test image $image after $tries attempts"
exit 1
