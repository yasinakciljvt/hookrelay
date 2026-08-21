#!/usr/bin/env bash
# HookRelay'i ayaga kaldirir ve hazir olana kadar bekler.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "▶ Imajlar derleniyor ve konteynerler baslatiliyor..."
docker compose up -d --build

wait_for () {
  local name=$1 url=$2 tries=${3:-60}
  printf "  %-18s" "$name"
  for i in $(seq 1 "$tries"); do
    if curl -fsS "$url" >/dev/null 2>&1; then echo "hazir"; return 0; fi
    sleep 2
  done
  echo "ZAMAN ASIMI"
  echo "    docker compose logs $name --tail=50"
  return 1
}

echo
echo "▶ Saglik kontrolleri:"
wait_for admin-api       http://localhost:8081/actuator/health
wait_for ingest-api      http://localhost:8082/actuator/health
wait_for dispatcher      http://localhost:8083/actuator/health
wait_for retry-scheduler http://localhost:8084/actuator/health
wait_for chaos-target    http://localhost:8085/actuator/health
wait_for gateway         http://localhost:8080/actuator/health
wait_for ui              http://localhost:3000

cat <<'BANNER'

  ✓ HookRelay ayakta

    Kontrol paneli   http://localhost:3000
    Kafka UI         http://localhost:8090
    Grafana          http://localhost:3001   (admin / admin)
    Prometheus       http://localhost:9090

  Demoyu calistirmak icin:  ./scripts/demo.sh
BANNER
