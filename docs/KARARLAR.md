# Mimari Kararlar (ADR)

Her karar: **ne**, **neden**, **ne feda edildi**. Bir kararı geri almak isteyen, neyi geri aldığını bilsin diye.

---

## ADR-01 — Fan-out ingest'te yapılır, dispatcher'da değil

**Karar.** Bir olay N endpoint'e gidiyorsa, N ayrı `DeliveryTask` kaydı `ingest-api` içinde üretilir.

**Neden.** Kafka kaydının key'i `endpointId` olmalı ki "aynı endpoint'e sıralı teslimat" garantisi partition'dan bedava gelsin. Fan-out dispatcher'da yapılsaydı key `messageId` olurdu ve o garanti kaybolurdu.

**Feda edilen.** Yazma amplifikasyonu: 1 olay → N outbox satırı. 100 endpoint'e abone bir uygulama, tek olayda 100 satır yazar.

---

## ADR-02 — Gecikme süresi topic adında yer almaz

**Karar.** Topic'ler `retry.t1..t5`, `retry.10s` değil. Süreler konfigürasyondan gelir.

**Neden.** Gecikme bir çalışma zamanı ayarı. Topic adına yazılırsa süreyi değiştirmek topic'i yeniden adlandırmak demek — içindeki bekleyen mesajların kaybı.

**Feda edilen.** Topic adına bakıp gecikmeyi anlayamıyorsunuz; konfigürasyona bakmak gerekiyor.

---

## ADR-03 — Konfigürasyon compacted topic ile replike edilir

**Karar.** `admin-api` endpoint/uygulama değişikliklerini compacted topic'e basar; `ingest` ve `dispatcher` Redis'e replike eder ve oradan okur.

**Neden.** Sıcak yolda servisler arası HTTP olmasın. Sonuç: kontrol düzlemi çökse bile veri düzlemi çalışır, gecikme ~0.2 ms.

**Feda edilen.** Nihai tutarlılık. Bir endpoint kapatıldığında replikaya ulaşması birkaç yüz milisaniye sürer; o aralıkta bir teslimat daha gidebilir.

**Alternatif neden seçilmedi.** HTTP + cache: cache invalidation problemi geri gelir, admin-api sıcak yola girer. Paylaşılan veritabanı: iki servis aynı tabloya bağlanır, mikroservis olmaktan çıkar.

---

## ADR-04 — Devre kesici Redis'te, Resilience4j'de değil

**Karar.** Endpoint bazlı devre kesici kendi Lua betiğimizle Redis'te tutulur.

**Neden.** Resilience4j'nin devresi JVM içinde yaşar. N dispatcher örneği = N ayrı devre; her biri ayrı ayrı eşiği doldurur, örnek ölünce durum kaybolur. Endpoint bazlı devre paylaşılan durum ister.

**Feda edilen.** Her karar için bir Redis gidiş-dönüşü (~0.2 ms). Redis çökerse devre kesici de çöker.

---

## ADR-05 — İdempotency iki katmanlı

**Karar.** Redis (hız) + Postgres UNIQUE kısıtı (doğruluk).

**Neden.** Redis bir cache'tir: bellekten taşabilir, restart'ta boşalabilir. Tek başına Redis'e güvenen idempotency, "çoğu zaman çalışan" idempotency'dir. Tek başına veritabanı ise her istekte disk I/O.

**Feda edilen.** İki yerde durum tutmanın karmaşıklığı; yarış durumunda `DataIntegrityViolationException` yakalayıp çözmek gerekir.

---

## ADR-06 — Offset işlemeden SONRA commit edilir

**Karar.** `ack-mode: manual_immediate`, işlem bittikten sonra ack.

**Neden.** "En az bir kez" teslimat. Webhook dünyasında "iki kez geldi" çözülebilir bir problemdir (alıcı `X-HookRelay-Id` ile ayıklar), "hiç gelmedi" çözülemez.

**Feda edilen.** Mükerrer teslimat ihtimali. Azaltıldı ("zaten başarılı mı" kontrolü) ama sıfırlanmadı — sıfırlanamaz.

---

## ADR-07 — Başarısız teslimat da ack edilir

**Karar.** İşlem başarısız olsa bile offset ilerletilir; mesaj bir retry topic'ine taşınır.

**Neden.** Ack etmeseydik aynı kayıt aynı partition'da sonsuza kadar tekrar okunur ve **arkasındaki bütün mesajları bloklardı** (head-of-line blocking). Kafka'da "bu mesajı atla" diye bir şey yok.

**Feda edilen.** Retry topic'ine yazma başarısız olursa mesaj kaybolur. Bu yüzden o yazma `acks=all` ve idempotent üretici ile yapılır.

---

## ADR-08 — 4xx kalıcı hata sayılır (408 ve 429 hariç)

**Karar.** 400/401/403/404/410/422 → tek denemede DLQ.

**Neden.** Aynı isteği 5 kez daha göndermek aynı cevabı 5 kez daha almaktır. Boşa kaynak, boşa gecikme.

**Feda edilen.** Müşteri geçici olarak yanlış 403 dönüyorsa (örneğin token yenileme sırasında) teslimat gereksiz yere DLQ'ya düşer. `hookrelay.delivery.retry-on-4xx` ile açılabilir.

---

## ADR-09 — Outbox poller tek örnek çalışır

**Karar.** `ingest-api` yatay ölçeklenir; outbox poller ölçeklenmez.

**Neden.** `SKIP LOCKED` iki örneğe farklı satırlar verir ve A'daki eski kayıt B'deki yeniden **sonra** basılabilir. Sıra garantisi kırılır.

**Feda edilen.** Poller tek noktada darboğaz. Ölçülen kapasite: ~5.000 kayıt/sn, mevcut hedeflerin çok üstünde.

**Üretimde ne yapılır.** Leader election, key hash'ine göre bölme, ya da Debezium ile doğrudan WAL'dan okuma.

---

## ADR-10 — Servis keşfi için Eureka yok

**Karar.** Docker Compose DNS'i (`http://dispatcher:8083`) yeterli.

**Neden.** Eureka'nın çözdüğü problem — dinamik adresler ve DNS yokluğu — Compose'da da Kubernetes'te de yok. Çözmediği bir problem için beşinci bir süreç çalıştırmak, projeye anlaşılmaz bir parça eklemektir.

**Feda edilen.** DNS'i olmayan bir ortama taşınırsa servis keşfi eklemek gerekir.

---

## ADR-11 — Üç veritabanı, tek Postgres sunucusu

**Karar.** `hookrelay_control`, `hookrelay_ingest`, `hookrelay_delivery` — ayrı veritabanı, aynı sunucu.

**Neden.** İzolasyon şema düzeyinde gerçek: hiçbir servis diğerinin tablosunu göremez. Üç ayrı konteyner ise yerel geliştirmede 600 MB fazladan RAM.

**Feda edilen.** Kaynak izolasyonu yok — bir servisin ağır sorgusu diğerlerini yavaşlatabilir. Üretimde ayırmak bir bağlantı dizgisi değişikliği.

---

## ADR-12 — Redis `maxmemory-policy: noeviction`

**Karar.** Bellek dolunca Redis yazma reddeder, anahtar silmez.

**Neden.** Redis burada sadece cache değil — endpoint konfigürasyon **replikası**. `allkeys-lru` olsaydı Redis bellek dolunca konfigürasyonları sessizce silerdi ve dispatcher teslimatları "endpoint bulunamadı" diye düşürürdü.

**Feda edilen.** Bellek dolduğunda yazmalar patlar. Gürültülü bir hata, sessiz veri kaybından iyidir.

---

## ADR-13 — Sağlık projeksiyonu yalnızca gerçek denemeleri sayar

**Karar.** `DeliveryResult.Outcome` beş değer taşır ve `isRealAttempt()` ile ikiye ayrılır. Projeksiyon `SHORT_CIRCUITED` ve `DISCARDED` sonuçlarını **hata olarak saymaz**; kısa devreler ayrı bir `short_circuited` sütununda birikir.

**Neden.** İlk sürüm "SUCCEEDED değilse hatadır" diyordu. Sonuç ölçüldü: silinmiş bir endpoint'in 25.657 düşürülmüş teslimatı, o endpoint'in "25.657 kez hata verdiği" gibi görünüyordu — oysa ona tek bir istek bile atılmamıştı. Devresi açık bir endpoint de hiç istek almadığı hâlde saniyede yüzlerce "hata" biriktiriyordu.

**Feda edilen.** Bir sütun, bir migration, bir enum metodu. Karşılığında "başarı oranı" metriği gerçekten başarı oranını ölçüyor.

> Yanlış bir metrik, olmayan bir metrikten kötüdür: olmayana bakmazsınız, yanlış olana güvenirsiniz.

---

## ADR-14 — Kısıt ihlali yakalanmaz, `ON CONFLICT` ile önlenir

**Karar.** Mükerrer `delivery_attempt` kaydı, `INSERT ... ON CONFLICT DO NOTHING` ile veritabanında sessizce yutulur. `DataIntegrityViolationException` yakalanmaz.

**Neden.** İlk sürüm istisnayı yakalayıp devam ediyordu:

```java
try { attempts.save(...); }
catch (DataIntegrityViolationException e) { log.debug("zaten var"); }
deliveries.save(delivery);        // ← artık çalışmaz
```

Hibernate bir kısıt ihlali gördüğünde persistence context'i tutarsız kabul eder ve transaction'ı **rollback-only** işaretler. İstisnayı yakalamak bunu değiştirmez: sonraki yazmalar çalışır *görünür*, commit anında `UnexpectedRollbackException` gelir ve tüm transaction geri sarılır.

Yani teslimatın durum güncellemesi, tam da mükerrer teslimat senaryosunda — Kafka'da **normal** olan durumda — kayboluyordu.

**Feda edilen.** JPQL yerine native sorgu; Postgres'e bağımlılık (`ON CONFLICT` standart SQL değil, MySQL'de `INSERT IGNORE`).

---

## ADR-15 — Filtreleme veritabanında yapılır, sayfalamadan sonra değil

**Karar.** Toplu yeniden gönderim, endpoint filtresini repository sorgusuna verir.

**Neden.** İlk sürüm 500 kayıt çekip Java'da filtreliyordu. "En fazla 500 gönder" dendiğinde, o 500'ün içinde hedef endpoint'ten 12 tane varsa 12 tane gönderiliyordu — sessizce eksik iş, ve kullanıcı 500 gönderildiğini sanıyordu.

**Feda edilen.** Bir repository metodu daha.
