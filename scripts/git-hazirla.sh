#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Depoyu faz faz commit'lerle hazirlar.
#
# Neden: tek "initial commit" ile yuklenmis proje, hazir sablondan
# indirilmis gibi gorunur. Faz faz gecmis, projenin nasil buyudugunu
# ve her fazin calisir durumda birakildigini gosterir.
#
# Kullanim: ./scripts/git-hazirla.sh
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

[[ -d .git ]] && { echo "Zaten bir git deposu var. Devam edilmiyor."; exit 1; }

git init -q -b main
git add .gitignore .editorconfig LICENSE pom.xml
git commit -q -m "chore: cok modullu Maven iskeleti"

git add libs/contracts
git commit -q -m "feat: contracts - topic katalogu ve olay semalari"

git add libs/common
git commit -q -m "feat: common - HMAC imzalama ve Redis Lua ilkelleri

Hiz siniri, dagitik devre kesici ve idempotency deposu Lua ile yazildi:
oku-hesapla-yaz ucusu atomik olmak zorunda."

git add libs/outbox
git commit -q -m "feat: outbox - transactional outbox deseni

Veritabani commit'i ile Kafka yayini tek transaction'a giremez.
Kayit ayni transaction'da bir tabloya yazilir, ayri bir poller basar."

git add services/admin-api
git commit -q -m "feat: admin-api - kontrol duzlemi ve compacted config replikasyonu"

git add services/ingest-api
git commit -q -m "feat: ingest-api - idempotent olay kabulu ve fan-out"

git add services/dispatcher
git commit -q -m "feat: dispatcher - teslimat hatti, devre kesici, bulkhead

Chain of Responsibility ile on kontroller, endpoint bazli bulkhead,
Strategy ile katmanli yeniden deneme politikasi. Hata siniflandirmasi
ayri bir bean'de (FailureClassifier): siniflandirma bir politikadir,
veri degil."

git add services/retry-scheduler
git commit -q -m "feat: retry-scheduler - pause/seek ile Kafka'da geciktirmeli mesaj"

git add services/chaos-target services/gateway
git commit -q -m "feat: chaos-target ve gateway"

git add Dockerfile .dockerignore docker-compose.yml ops scripts ui
git commit -q -m "feat: ops - compose, nginx, prometheus, grafana, k6, kontrol paneli"

git add README.md README.en.md CONTRIBUTING.md .github docs
git commit -q -m "docs: README, mimari kararlar (ADR) ve sifirdan insa rehberi"

git add .github CONTRIBUTING.md LICENSE .editorconfig 2>/dev/null || true
git diff --cached --quiet || git commit -q -m "ops: CI, lisans ve kod kurallari"

git add -A
git diff --cached --quiet || git commit -q -m "chore: kalan dosyalar"

echo "✓ $(git rev-list --count HEAD) commit olusturuldu"
git --no-pager log --oneline
