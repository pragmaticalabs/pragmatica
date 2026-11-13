### Fixed (2026-11-13 — #3050, #3069: db-migrations history-table bootstrap on SQL Server and Oracle)
- **`db-migrations` bootstrap:** the history-table bootstrap now works on SQL Server (`IF OBJECT_ID(...) IS NULL CREATE TABLE` instead of the invalid `CREATE TABLE IF NOT EXISTS`, #3050) and on Oracle (`DEFAULT` before `NOT NULL`, no more ORA-03076, #3069).
