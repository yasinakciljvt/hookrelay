#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Uctan uca demo.
#
# Bir uygulama ve dort endpoint kurar, 40 olay gonderir, ne oldugunu anlatir.
# Amac: yeniden deneme, devre kesici, kalici hata ve DLQ davranislarinin
# hepsini tek komutta gozle gorulebilir kilmak.
# ---------------------------------------------------------------------------
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
CHAOS_INTERNAL=${CHAOS_INTERNAL:-http://chaos-target:8085}

need () { command -v "$1" >/dev/null || { echo "$1 gerekli"; exit 1; }; }
need curl; need jq

echo "▶ 1/5  Uygulama olusturuluyor"
APP=$(curl -fsS -XPOST "$BASE/api/admin/applications" \
      -H 'Content-Type: application/json' \
      -d "{\"name\":\"demo-$(date +%s)\"}")
APP_ID=$(jq -r '.application.id' <<<"$APP")
API_KEY=$(jq -r '.apiKey'        <<<"$APP")
echo "   uygulama: $APP_ID"
echo "   anahtar : $API_KEY"

add_endpoint () {
  curl -fsS -XPOST "$BASE/api/admin/applications/$APP_ID/endpoints" \
    -H 'Content-Type: application/json' \
    -d "{\"url\":\"$1\",\"description\":\"$2\",\"eventTypes\":[\"*\"]}" \
    | jq -r '.id'
}

echo
echo "▶ 2/5  Endpoint'ler ekleniyor"
EP_OK=$(add_endpoint    "$CHAOS_INTERNAL/sink/ok"            "saglam")
EP_FLAKY=$(add_endpoint "$CHAOS_INTERNAL/sink/flaky?rate=60" "%60 hatali")
EP_DEAD=$(add_endpoint  "$CHAOS_INTERNAL/sink/dead"          "olu sunucu")
EP_GONE=$(add_endpoint  "$CHAOS_INTERNAL/sink/gone"          "410 Gone")
echo "   saglam     $EP_OK"
echo "   hatali     $EP_FLAKY"
echo "   olu        $EP_DEAD"
echo "   410 Gone   $EP_GONE"

echo
echo "▶ 3/5  Konfigurasyonun compacted topic uzerinden dispatcher'a ulasmasi bekleniyor"
sleep 3

echo
echo "▶ 4/5  40 olay gonderiliyor (4 endpoint x 40 = 160 teslimat)"
for i in $(seq 1 40); do
  curl -fsS -XPOST "$BASE/v1/events" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $API_KEY" \
    -H "Idempotency-Key: demo-$i" \
    -d "{\"eventType\":\"order.paid\",\"payload\":{\"no\":$i,\"tutar\":$((RANDOM % 5000)).00}}" \
    >/dev/null &
done
wait
echo "   gonderildi"

echo
echo "▶ 5/5  Ayni Idempotency-Key ile 5 kez daha (mukerrer testi)"
for i in 1 2 3 4 5; do
  R=$(curl -fsS -XPOST "$BASE/v1/events" \
      -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $API_KEY" \
      -H "Idempotency-Key: demo-1" \
      -d '{"eventType":"order.paid","payload":{"no":1}}' | jq -r '.duplicate')
  echo "   deneme $i → duplicate=$R"
done

cat <<BANNER

  ─────────────────────────────────────────────────────────────
  Simdi http://localhost:3000 adresini acin ve izleyin:

   • saglam endpoint    → hepsi ilk denemede basarili
   • %60 hatali         → t1, t2, t3 katmanlarinda ustel azalma
   • olu sunucu         → 5 hatadan sonra DEVRE ACIK, istek atilmiyor
   • 410 Gone           → tek denemede DLQ, yeniden denenmiyor

  Zamanlayici kartinda duraklatilmis katmanlari sari gorursunuz.

  Grafana:  http://localhost:3001/d/hookrelay-main
  Kafka UI: http://localhost:8090   (retry topic'lerine bakin)

  Yuk testi:
    k6 run -e API_KEY=$API_KEY ops/k6/load.js
  ─────────────────────────────────────────────────────────────
BANNER
