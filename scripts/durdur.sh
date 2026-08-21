#!/usr/bin/env bash
# Durdurur. --temizle verilirse veri hacimlerini de siler.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "--temizle" ]]; then
  echo "▶ Konteynerler ve VERI HACIMLERI siliniyor"
  docker compose down -v
else
  echo "▶ Konteynerler durduruluyor (veri korunuyor)"
  echo "  Veriyi de silmek icin: $0 --temizle"
  docker compose down
fi
