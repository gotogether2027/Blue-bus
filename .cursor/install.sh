#!/usr/bin/env bash
# Idempotent repository bootstrap for the BLUE BUS backend.
# Installs system dependencies, provisions the local PostgreSQL database,
# and warms the Maven dependency cache. Safe to run repeatedly.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

DB_NAME="${DB_NAME:-bluebus}"
DB_USERNAME="${DB_USERNAME:-bluebus}"
DB_PASSWORD="${DB_PASSWORD:-bluebus_local_dev}"

echo "==> Installing system packages (PostgreSQL, Docker)"
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update -qq
sudo apt-get install -y -qq postgresql postgresql-client docker.io

echo "==> Configuring Docker to use the vfs storage driver (works in nested VMs)"
# Testcontainers-based integration tests need Docker. The default overlayfs
# snapshotter cannot mount overlay-on-overlay in this nested environment, so
# fall back to the vfs storage driver.
sudo mkdir -p /etc/docker
echo '{"storage-driver":"vfs","features":{"containerd-snapshotter":false}}' \
  | sudo tee /etc/docker/daemon.json >/dev/null

echo "==> Ensuring PostgreSQL cluster is running"
sudo pg_ctlcluster 16 main start 2>/dev/null || true

echo "==> Provisioning database role and database (idempotent)"
sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='${DB_USERNAME}') THEN
    CREATE ROLE ${DB_USERNAME} LOGIN PASSWORD '${DB_PASSWORD}';
  END IF;
END \$\$;
SQL
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" \
  | grep -q 1 || sudo -u postgres createdb -O "${DB_USERNAME}" "${DB_NAME}"
sudo -u postgres psql -d "${DB_NAME}" -v ON_ERROR_STOP=1 \
  -c "GRANT ALL ON SCHEMA public TO ${DB_USERNAME}; ALTER SCHEMA public OWNER TO ${DB_USERNAME};"

echo "==> Writing local .env (git-ignored) if absent"
if [ ! -f "${REPO_ROOT}/.env" ]; then
  cat > "${REPO_ROOT}/.env" <<ENV
DB_HOST=localhost
DB_PORT=5432
DB_NAME=${DB_NAME}
DB_USERNAME=${DB_USERNAME}
DB_PASSWORD=${DB_PASSWORD}
SERVER_PORT=8080
JWT_SECRET=local-dev-only-not-a-real-secret-please-change-0123456789abcdef
JWT_ISSUER=blue-bus
ENV
fi

echo "==> Warming Maven dependency cache and building"
chmod +x "${REPO_ROOT}/mvnw"
"${REPO_ROOT}/mvnw" -q -DskipTests package

echo "==> install.sh complete"
