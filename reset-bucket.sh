#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source crates_server.env; set +a

docker exec -e MC_HOST_local="http://$STORAGE_ACCESS_KEY:$STORAGE_SECRET_KEY@localhost:9000" \
  minio_server mc rm --recursive --force local/content-bucket/ >/dev/null

echo "content-bucket 비움"