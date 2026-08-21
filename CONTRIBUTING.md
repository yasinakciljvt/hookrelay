# Katkı ve kod kuralları

Bu depo tek kişilik bir proje ama kurallar yazılı — çünkü yazılmayan kural,
altı ay sonra hatırlanmayan kuraldır.

## Kod

**Yorumlar `ne` değil `neden` anlatır.** Kodun ne yaptığı kodda yazıyor.
Yorumda yalnızca kodu okuyarak öğrenilemeyecek şey bulunur: hangi alternatif
elendi, neyin bedeli ödendi, hangi tuzağa düşülmemesi gerekiyor.

```java
// Kötü
counter.increment();                 // sayacı artır

// İyi
// Deneme sayısı ARTMIYOR: aynı attempt ile geri konuyor.
// Müşterinin hatası olmayan bir engelleme, onun deneme hakkını yakmamalı.
int attempt = block.consumesAttempt() ? task.attempt() + 1 : task.attempt();
```

Bu depoda yorum oranı normalden yüksek (~%20). Bilinçli: proje bir öğrenme
artefaktı ve `docs/` altındaki rehberle birlikte okunuyor. Yine de bir yorum
kodu tekrar ediyorsa silinir.

**Kod içinde Türkçe karakter kullanılmaz.** Yorumlar ve log mesajları ASCII:
`icin`, `degil`, `musteri`. Sebebi editör/terminal/CI arasındaki kodlama
sorunlarından kaçınmak. Markdown ve PDF dokümanlarında tam Türkçe kullanılır.

**Satır uzunluğu 100.** `.editorconfig` uygular.

## Mimari kararlar

Kalıcı bir karar veriyorsanız `docs/KARARLAR.md` dosyasına bir ADR ekleyin:
**ne**, **neden**, **ne feda edildi**. Üçüncüsü zorunlu — bedeli yazılmayan
karar, karar değil tahmindir.

## Commit mesajları

```
<tip>: <kısa özet, emir kipi, 72 karakteri geçmez>

Gerekirse gövde: neden bu değişiklik gerekti, hangi alternatif elendi.
```

Tipler: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `chore`, `ops`.

## Test

```bash
mvn test      # birim testleri, altyapı gerektirmez
mvn verify    # + Testcontainers entegrasyon testleri (Docker gerekir)
```

`*Test` surefire ile, `*IT` failsafe ile koşar. Günlük geliştirmede
`mvn test` beş saniyede bitmeli; entegrasyon testleri konteyner kaldırdığı
için ayrı tutulur.

**Bir hata düzeltiyorsanız önce onu deterministik olarak üretin.**
Üretemiyorsanız düzelttiğinizi de kanıtlayamazsınız.
