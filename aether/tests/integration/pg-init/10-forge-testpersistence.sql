-- #598: test-persistence's named datasource (database.testpersistence) points at its
-- OWN physical database — the schema history/owner tables are fixed-name-per-physical-
-- database, so sharing `forge` with url-shortener would re-create the migration-owner
-- collision this layout exists to remove. Runs via docker-entrypoint-initdb.d on every
-- fresh pgdata volume; deploy_docker drops aether_pgdata before compose up, so every
-- deploy re-runs it. (A --skip-deploy run against a cluster deployed BEFORE this file
-- existed will lack the database — redeploy once.)
CREATE DATABASE forge_testpersistence;
GRANT ALL PRIVILEGES ON DATABASE forge_testpersistence TO forge;
