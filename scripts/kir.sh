#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# KIRMA SENARYOLARI — ogrenmenin asil gerceklestigi yer.
#
# Kullanim: ./scripts/kir.sh <senaryo>
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-}" in
  broker)
    echo "▶ Kafka durduruluyor. Simdi olay gondermeyi deneyin."
    echo "  Beklenen: ingest 202 dondurmeye DEVAM EDER (outbox'a yaziyor),"
    echo "            teslimat durur, Kafka gelince birikmis hepsi akar."
    docker compose stop kafka
    echo
    echo "  Geri acmak icin: docker compose start kafka"
    ;;
  redis)
    echo "▶ Redis durduruluyor."
    echo "  Beklenen: dispatcher endpoint konfigurasyonunu okuyamaz,"
    echo "            teslimatlar dusurulur. Redis'in bu projede sadece"
    echo "            'cache' olmadigini gosterir."
    docker compose stop redis
    echo "  Geri: docker compose start redis && curl -XPOST localhost:8080/api/admin/endpoints/republish"
    ;;
  cokuk-tuketici)
    echo "▶ Dispatcher olduruluyor (SIGKILL) — offset commit edilmeden."
    echo "  Beklenen: yeniden basladiginda commit edilmemis mesajlari"
    echo "            TEKRAR isler. 'En az bir kez' teslimatin canli kaniti."
    docker compose kill -s SIGKILL dispatcher
    sleep 2
    docker compose start dispatcher
    ;;
  olcekle)
    N=${2:-3}
    echo "▶ Dispatcher $N ornege cikariliyor."
    echo "  Kafka UI'da (localhost:8090) tuketici grubuna bakin:"
    echo "  12 partition $N ornek arasinda paylastirilacak."
    docker compose up -d --scale dispatcher=$N --no-recreate dispatcher
    ;;
  *)
    cat <<'HELP'
Senaryolar:

  broker           Kafka'yi durdurur   → outbox'in ne ise yaradigini gosterir
  redis            Redis'i durdurur    → replikanin kritikligini gosterir
  cokuk-tuketici   Dispatcher'i oldurur→ "en az bir kez" teslimati gosterir
  olcekle [N]      N ornege cikarir    → partition paylasimini gosterir

Her senaryodan once http://localhost:3000 acik olsun.
HELP
    ;;
esac
