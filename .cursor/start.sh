#!/usr/bin/env bash
# Per-boot startup for the BLUE BUS backend environment.
# Starts PostgreSQL and the Docker daemon, and ensures the database exists.
# Must be idempotent and tolerate restarts.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

DB_NAME="${DB_NAME:-bluebus}"
DB_USERNAME="${DB_USERNAME:-bluebus}"
DB_PASSWORD="${DB_PASSWORD:-bluebus_local_dev}"

echo "==> Starting PostgreSQL cluster"
sudo pg_ctlcluster 16 main start 2>/dev/null || true

# Wait for PostgreSQL to accept connections.
for _ in $(seq 1 30); do
  if sudo -u postgres pg_isready -q; then break; fi
  sleep 1
done

echo "==> Ensuring database role and database exist (idempotent)"
sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL || true
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='${DB_USERNAME}') THEN
    CREATE ROLE ${DB_USERNAME} LOGIN PASSWORD '${DB_PASSWORD}';
  END IF;
END \$\$;
SQL
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" \
  | grep -q 1 || sudo -u postgres createdb -O "${DB_USERNAME}" "${DB_NAME}"

echo "==> Starting Docker daemon (for Testcontainers integration tests)"
if ! sudo docker info >/dev/null 2>&1; then
  sudo nohup dockerd >/var/log/dockerd.log 2>&1 &
  for _ in $(seq 1 30); do
    if sudo docker info >/dev/null 2>&1; then break; fi
    sleep 1
  done
fi
# Allow the non-root user to reach the Docker socket.
[ -S /var/run/docker.sock ] && sudo chmod 666 /var/run/docker.sock || true

echo "==> start.sh complete"
