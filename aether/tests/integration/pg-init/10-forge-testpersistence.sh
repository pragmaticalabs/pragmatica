#!/bin/bash
# #598: create test-persistence's own physical database (its named datasource
# database.testpersistence must not share url-shortener's physical DB — the schema
# history/owner tables are fixed-name-per-physical-database). Shell form, not .sql:
# pg-parser's CorpusParseTest feeds EVERY .sql in the repo through the MIGRATION
# grammar, and CREATE DATABASE / GRANT ... ON DATABASE are admin DDL outside its
# domain (broke CI on 30a91eb85). Runs via docker-entrypoint-initdb.d on every fresh
# pgdata volume; deploy_docker drops aether_pgdata before compose up, so every deploy
# re-runs it. (A --skip-deploy run against a cluster deployed BEFORE this file existed
# will lack the database — redeploy once.)
set -e
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
     -c "CREATE DATABASE forge_testpersistence" \
     -c "GRANT ALL PRIVILEGES ON DATABASE forge_testpersistence TO forge"
