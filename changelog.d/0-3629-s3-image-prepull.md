S3 tests workflow pre-pulls the digest-pinned MinIO image with 3 bounded retries before Maven, so a transient cgr.dev failure no longer errors the run (#3629).
