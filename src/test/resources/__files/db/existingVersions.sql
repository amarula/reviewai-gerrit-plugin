CREATE TABLE db_versions (
  version INT PRIMARY KEY,
  applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO db_versions(version, applied_at) VALUES (0, TIMESTAMP '2026-01-01 00:00:00');
