# HookRelay

[![CI](https://github.com/yasinakciljvt/hookrelay/actions/workflows/ci.yml/badge.svg)](https://github.com/yasinakciljvt/hookrelay/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

**Güvenilir webhook teslimat servisi.** Bir olayı müşterinin sunucusuna, o sunucu çökse, yavaşlasa, 429 dönse veya üç saat kapalı kalsa bile teslim eder.

Spring Boot 3 · Java 21 · Kafka · Redis · PostgreSQL · Docker

```bash
git clone https://github.com/yasinakciljvt/hookrelay.git && cd hookrelay
./scripts/baslat.sh     # her şeyi derler, ayağa kaldırır, hazır olana kadar bekler
./scripts/demo.sh       # 1 uygulama + 4 endpoint kurar, 40 olay gönderir
```

→ **http://localhost:3000**

---

## Çözdüğü problem

Bir SaaS ürününüz var ve müşterilerinize olay bildirimi göndermeniz gerekiyor. Basit görünür:

```java
restClient.post().uri(customer.url()).body(event).retrieve();
```

Sonra gerçek dünya devreye girer:

| Ne oluyor | Naif kodun sonucu |
|---|---|
| Müşterinin sunucusu 500 dönüyor | Olay kaybolur |
| Sunucu 30 saniyede cevap veriyor | İş parçacığınız bloke, diğer müşteriler bekliyor |
| Sunucu 3 saat kapalı | 3 saatlik olayların hepsi kayıp |
| Sunucu saniyede 10 istek kaldırıyor | Siz 500 atıp deviriyorsunuz |
| Aynı olayı iki kez gönderdiniz | Müşteri iki kez sipariş oluşturuyor |
| "Webhook gelmedi" şikâyeti | Elinizde hiçbir kayıt yok |

Stripe, GitHub ve Shopify bu problemi çözmek zorunda kaldı. [Svix](https://svix.com) ve [Hookdeck](https://hookdeck.com) bunun üzerine şirket kurdu. HookRelay aynı problemin açık, okunabilir ve **öğretici** bir uygulaması.

---

## Mimari

```
                    ┌──────────────┐
   müşteri ───────► │  ingest-api  │  olayı al, outbox'a yaz, 202 dön
                    └──────┬───────┘
                           │ Transactional Outbox
                           ▼
              ╔════════════════════════╗
              ║  hookrelay.messages.v1 ║  key = endpointId
              ║      12 partition      ║  → aynı endpoint'e sıralı teslimat
              ╚═══════════┬════════════╝
                          ▼
                   ┌─────────────┐
                   │ dispatcher  │  devre kesici → hız sınırı → bulkhead
                   └──┬───┬───┬──┘  → HMAC imzala → HTTP POST
           başarılı   │   │   │   başarısız
              ────────┘   │   └────────┐
                          │            ▼
                  kalıcı hata     ╔═════════════════════════╗
                     (4xx)        ║ hookrelay.retry.t1..t5  ║
                        │         ╚════════════┬════════════╝
                        │                      │ pause + seek
                        ▼                      ▼
                  ╔══════════╗        ┌──────────────────┐
                  ║   DLQ    ║◄───────│ retry-scheduler  │
                  ╚══════════╝        └──────────────────┘

  ┌───────────┐  compacted topic'ler   ┌─────────────────────┐
  │ admin-api ├───────────────────────►│ Redis replikası      │
  │ (kontrol) │  endpoint-config       │ ingest + dispatcher  │
  └───────────┘  app-config            │ okur — HTTP yok      │
                                       └─────────────────────┘
```

### Servisler

| Servis | Port | Sahibi olduğu veri | Ne yapar |
|---|---|---|---|
| `gateway` | 8080 | — | Tek giriş kapısı |
| `admin-api` | 8081 | uygulama, endpoint, sağlık projeksiyonu | Kontrol düzlemi |
| `ingest-api` | 8082 | mesaj, outbox | Olay kabulü, idempotency, fan-out |
| `dispatcher` | 8083 | teslimat, deneme kaydı | HTTP gönderim, yeniden deneme kararı |
| `retry-scheduler` | 8084 | *(durumsuz)* | Gecikmeli yeniden denemeler |
| `chaos-target` | 8085 | *(bellekte)* | Demo kurbanı — bozuk müşteri sunucusu |

**Kural:** bir veriyi yalnız bir servis yazar. `dispatcher` endpoint tablosunu okumak için `admin-api`'ye HTTP atmaz — konfigürasyonu **compacted Kafka topic**'inden Redis'e replike eder. Sonuç: `admin-api` tamamen çökse bile teslimat devam eder.

---

## Kafka'nın burada gerçekten çözdüğü şey

### 1. Sıra garantisi bedava geliyor

`key = endpointId` → aynı endpoint'in bütün olayları aynı partition'a düşer → Kafka partition içinde sırayı garanti eder. "Aynı müşteriye olaylar sırayla gitsin" iş kuralı, bir altyapı özelliğine dönüşüyor. Bunun için tek satır sıralama kodu yazmıyoruz.

### 2. Kafka'da geciktirmeli mesaj — ve nasıl kurulduğu

Kafka bir kuyruk değil, bir **günlüktür**. "Bu mesajı 30 dakika sonra teslim et" diye bir işlem **yoktur**.

Naif çözüm — `Thread.sleep(30 dakika)` — felakettir: `max.poll.interval.ms` (varsayılan 5 dakika) aşılır, broker tüketiciyi ölmüş sayar, rebalance tetiklenir, mesaj başkasına gider, o da uyur. Bütün grup kilitlenir.

**Çözüm: her gecikme için ayrı topic + `pause`/`seek`.**

```java
if (now < notBefore) {
    consumer.pause(List.of(partition));      // bu partition'dan kayıt gelmesin
    consumer.seek(partition, record.offset()); // offset'i geri al, kayıt kaybolmasın
    resumeAt.put(partition, notBefore);
    break;
}
// poll() çağrılmaya devam eder → heartbeat gider → rebalance olmaz
```

Neden sadece baştaki kayda bakmak yetiyor: bir katman topic'indeki **bütün kayıtların gecikmesi aynı**. Dolayısıyla `not-before` sırası = offset sırası. Baştaki hazır değilse arkasındakiler de değildir.

Karışık gecikmeler tek topic'te olsaydı bu çalışmazdı — arkadaki hazır bir kaydı işlemek için öndekini "atlamak" gerekirdi ve Kafka'da bu mümkün değil. **Katmanlı topic tasarımının asıl sebebi bu.**

→ [`RetrySchedulerRunner.java`](services/retry-scheduler/src/main/java/dev/hookrelay/retryscheduler/runner/RetrySchedulerRunner.java)

### 3. Compacted topic = replike edilebilir tablo

`cleanup.policy=compact` ile Kafka eski kayıtları silmez, **aynı key'in eski sürümlerini** siler. Topic, "her key için son değer" tutan bir tabloya dönüşür.

Yeni bir `dispatcher` ayağa kalktığında topic'i baştan okur ve saniyeler içinde bütün endpoint tablosunu öğrenir. Her örnek **kendi tüketici grubunda** — çünkü bu bir iş kuyruğu değil bir durum tablosu; iş bölüşmek değil, herkesin her şeyi bilmesi gerekiyor.

→ [`EndpointConfigReplicator.java`](libs/common/src/main/java/dev/hookrelay/common/kafka/EndpointConfigReplicator.java)

---

## Redis'in altı ayrı işi

Hiçbiri "cache" değil.

| İş | Nasıl | Neden Lua |
|---|---|---|
| Hız sınırı | token bucket | oku-hesapla-yaz **atomik** olmalı |
| Devre kesici | durum makinesi | durum **örnekler arası** paylaşılmalı |
| Idempotency | SETNX + TTL | kontrol ve rezervasyon tek adımda |
| Endpoint replikası | JSON + set indeksi | sıcak yolda HTTP olmasın |
| API anahtarı ters indeksi | hash → appId | doğrulama ~0.2 ms |
| Bulkhead | *(JVM içi semafor)* | süreç yerel — Redis gerekmez |

**Neden Resilience4j değil:** Resilience4j'nin devre kesicisi JVM içinde yaşar. 4 dispatcher örneği çalıştırınca 4 ayrı devre olur, her biri ayrı ayrı eşiği doldurmak zorunda kalır, örnek ölünce durum kaybolur. Endpoint bazlı devre **paylaşılan durum** ister.

→ [`circuit_breaker.lua`](libs/common/src/main/resources/lua/circuit_breaker.lua)

---

## Design pattern'ler

Her biri gerçek bir problemden çıktı. Kullanılmayan soyutlama yok.

| Pattern | Nerede | Hangi problem |
|---|---|---|
| **Transactional Outbox** | [`OutboxPublisher`](libs/outbox/src/main/java/dev/hookrelay/outbox/OutboxPublisher.java) | DB commit'i ile Kafka publish'i atomik değil |
| **Chain of Responsibility** | [`PreflightCheck`](services/dispatcher/src/main/java/dev/hookrelay/dispatcher/delivery/checks/PreflightCheck.java) | Gönderim öncesi kontroller büyüyor |
| **Strategy** | [`RetryPolicy`](services/dispatcher/src/main/java/dev/hookrelay/dispatcher/delivery/RetryPolicy.java) | Müşteri bazlı yeniden deneme politikası |
| **Bulkhead** | [`EndpointBulkhead`](services/dispatcher/src/main/java/dev/hookrelay/dispatcher/delivery/EndpointBulkhead.java) | Yavaş müşteri herkesi bloke ediyor |
| **Circuit Breaker** | [`RedisCircuitBreaker`](libs/common/src/main/java/dev/hookrelay/common/redis/RedisCircuitBreaker.java) | Ölü sunucuya 50.000 istek |
| **CQRS / projeksiyon** | [`DeliveryResultProjector`](services/admin-api/src/main/java/dev/hookrelay/adminapi/projection/DeliveryResultProjector.java) | "Bu endpoint sağlıklı mı" sorgusu |
| **Idempotent Consumer** | [`DeliveryProcessor`](services/dispatcher/src/main/java/dev/hookrelay/dispatcher/delivery/DeliveryProcessor.java) | Kafka en-az-bir-kez teslim eder |
| **State** | [`Delivery`](services/dispatcher/src/main/java/dev/hookrelay/dispatcher/domain/Delivery.java) | Teslimat yaşam döngüsü |

---

## Güvenlik

**HMAC-SHA256 imza**, Stripe'ın kullandığı biçimde:

```
X-HookRelay-Signature: t=1723459200,v1=8f3a...
imzalanan metin      = "{timestamp}.{gövde}"
```

Zaman damgası imzanın **içinde** olduğu için değiştirilemez; alıcı "5 dakikadan eskisini kabul etme" diyerek replay penceresini kapatır. Doğrulama sabit zamanlı (`MessageDigest.isEqual`) — `String.equals` erken çıkar ve kaç baytın tuttuğunu zamanlamayla sızdırır.

`chaos-target`'ın `/sink/strict` ucu imzayı **gerçekten doğrular** ve geçersizse 401 döner. Yani imza iddiası dokümanda değil, çalışan kodda kanıtlı.

Ayrıca: API anahtarları düz metin saklanmaz (yalnız SHA-256 hash), giden isteklerde yönlendirme takip edilmez (SSRF), konteynerler root değil.

---

## Kırma senaryoları

Öğrenmenin gerçekleştiği yer. Panel açıkken çalıştırın:

```bash
./scripts/kir.sh broker          # Kafka'yı durdur → outbox ne işe yarıyor?
./scripts/kir.sh redis           # Redis'i durdur → replika neden kritik?
./scripts/kir.sh cokuk-tuketici  # dispatcher'ı SIGKILL → "en az bir kez"
./scripts/kir.sh olcekle 3       # 3 örnek → 12 partition nasıl paylaşılıyor?
```

---

## Yük testi

Ölçülen (4 çekirdek / 8 GB, 13 konteyner aynı makinede):

| | |
|---|---|
| Yüksüz tek istek | **19 ms** (doğrudan), 25–60 ms (gateway üzerinden) |
| Steady, 200 sanal kullanıcı | ~79 olay/sn kabul, medyan 1.6 s, p95 4.9 s, **%0 hata** |
| Burst (idempotency) | 3610 istek → **10 benzersiz olay, 3600 mükerrer**, %0 hata |

Yüksüz gecikme 19 ms ve hiçbir istek hata vermiyor — yüksek p95 makinenin doyması, uygulamanın yavaşlığı değil. `p(95)<150` eşiği bilinçli olarak bırakıldı: eşik "kabul edilebilir olanı" söylemeli, "şu an olanı" değil.

```bash
k6 run -e API_KEY=hr_xxx ops/k6/load.js                  # 100 olay/sn, 2 dk
k6 run -e API_KEY=hr_xxx -e SCENARIO=spike ops/k6/load.js # 0 → 1000/sn
k6 run -e API_KEY=hr_xxx -e SCENARIO=burst ops/k6/load.js # idempotency yağmuru
```

---

## API

```bash
# Uygulama oluştur (API anahtarı SADECE burada, SADECE bir kez döner)
curl -XPOST localhost:8080/api/admin/applications \
  -H 'Content-Type: application/json' -d '{"name":"magazam"}'

# Endpoint ekle
curl -XPOST localhost:8080/api/admin/applications/$APP_ID/endpoints \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://ornek.com/webhook","eventTypes":["order.*"],"rateLimitPerSecond":10}'

# Olay gönder
curl -XPOST localhost:8080/v1/events \
  -H "Authorization: Bearer $API_KEY" \
  -H 'Idempotency-Key: siparis-4711' \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"order.paid","payload":{"orderId":4711,"amount":149.90}}'

# Teslimat günlüğü
curl localhost:8080/api/deliveries?status=EXHAUSTED
curl localhost:8080/api/deliveries/$DELIVERY_ID       # her denemenin detayı
curl -XPOST localhost:8080/api/deliveries/replay-exhausted
```

---

## Gözlemlenebilirlik

| | |
|---|---|
| Kontrol paneli | http://localhost:3000 |
| Grafana | http://localhost:3001/d/hookrelay-main |
| Prometheus | http://localhost:9090 |
| Kafka UI | http://localhost:8090 |

---

## Bu depoyu okurken

Yorum oranı normalden yüksek (~%20) ve bu bilinçli. Proje bir **öğrenme
artefaktı**: her önemli karar, kodun yanında *neden* öyle olduğuyla birlikte
duruyor — hangi alternatif elendi, neyin bedeli ödendi, hangi tuzak var.

En yoğun yorumlar en çok kanadığım yerlerde:
[`RetrySchedulerRunner`](services/retry-scheduler/src/main/java/dev/hookrelay/retryscheduler/runner/RetrySchedulerRunner.java) (Kafka'da geciktirmeli mesaj),
[`OutboxPublisher`](libs/outbox/src/main/java/dev/hookrelay/outbox/OutboxPublisher.java) (sıra garantisinin sınırı),
[`HttpClientDnsConfig`](services/gateway/src/main/java/dev/hookrelay/gateway/HttpClientDnsConfig.java) (Docker DNS önbelleği).

Kurallar [`CONTRIBUTING.md`](CONTRIBUTING.md) içinde. Uzun anlatım kodda değil,
[74 sayfalık rehberde](docs/HookRelay-Sifirdan-Insa-Rehberi.pdf).

## Gözden geçirme turu

Kod çalıştıktan sonra baştan okundu. Sekiz bulgu çıktı; ikisi gerçek hataydı:

| Bulgu | Neden ciddi |
|---|---|
| `DataIntegrityViolationException` yakalanıp devam ediliyordu | Hibernate transaction'ı `rollback-only` işaretler — sonraki yazmalar çalışır *görünür*, commit'te patlar. Tam da mükerrer teslimat senaryosunda. `ON CONFLICT DO NOTHING` ile önlendi |
| Sağlık projeksiyonu "SUCCEEDED değilse hata" sayıyordu | Silinmiş bir endpoint'in 25.657 düşürülmüş teslimatı "25.657 hata" gibi görünüyordu — hiç istek atılmamıştı. `Outcome.isRealAttempt()` ayrımı eklendi |

Kalan altısı: sessizce eksik iş yapan bir sayfalama/filtre sırası, iki metrik tutarsızlığı, çalışmayan bir `@Transactional`, `attempt - 1` gibi niyeti gizleyen bir satır, ölü bir `log` alanı.

Yöntem ve tam liste: rehberin **Bölüm 17**'si.

## Dürüst sınırlar

Bu bir öğrenme projesi. Bilinçli olarak yapılmamış olanlar:

- **Exactly-once yok.** Dağıtık sistemde yok zaten. En-az-bir-kez teslimat + `X-HookRelay-Id` ile idempotent alıcı, doğru cevap budur.
- **Outbox poller tek örnek.** Çok örnekte `SKIP LOCKED` sıra garantisini kırar. Üretimde: leader election, key'e göre bölme veya Debezium.
- **Üç veritabanı tek Postgres sunucusunda.** İzolasyon şema düzeyinde gerçek; ayırmak sadece bağlantı dizgisi değişikliği.
- **Tek Kafka broker, replication factor 1.** Yerel geliştirme için.
- **Kimlik doğrulama sadece API anahtarı.** Kontrol düzlemine kullanıcı girişi yok.

Her biri kodda gerekçesiyle yazılı — "unutulmuş" değil, "seçilmiş".

---

## Rehber

Projeyi sıfırdan kendiniz inşa etmek için adım adım rehber: [`docs/HookRelay-Sifirdan-Insa-Rehberi.pdf`](docs/HookRelay-Sifirdan-Insa-Rehberi.pdf) (67 sayfa)

## Lisans

MIT
