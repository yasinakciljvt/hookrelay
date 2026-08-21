package dev.hookrelay.contracts;

import java.util.List;

/**
 * Butun topic adlari tek yerde. Servisler string yazmaz, buradan okur.
 *
 * Isimlendirme: {urun}.{alan}.{surum}
 * Surum eki (.v1) sema kirilinca .v2 acip iki tuketiciyi yan yana calistirmayi mumkun kilar.
 */
public final class Topics {

    private Topics() {}

    /** Ana teslimat kuyrugu. key = endpointId → ayni endpoint'in olaylari sirali gider. */
    public static final String MESSAGES = "hookrelay.messages.v1";

    /**
     * Katmanli yeniden deneme topic'leri.
     *
     * Neden topic adinda sure YOK (t1 yerine 10s degil):
     * Gecikme suresi bir CALISMA ZAMANI ayari. "10s" topic adina yazilirsa
     * sureyi degistirmek topic'i yeniden adlandirmak demek olur, bu da
     * icindeki mesajlarin kaybi anlamina gelir. Katman numarasi sabit,
     * sure konfigurasyondan gelir.
     */
    public static final List<String> RETRY_TIERS = List.of(
            "hookrelay.retry.t1.v1",
            "hookrelay.retry.t2.v1",
            "hookrelay.retry.t3.v1",
            "hookrelay.retry.t4.v1",
            "hookrelay.retry.t5.v1"
    );

    /** Tum denemeleri tuketmis mesajlar. Elle kurtarilir, otomatik silinmez. */
    public static final String DLQ = "hookrelay.dlq.v1";

    /** Her teslimat denemesinin sonucu. Metrik ve projeksiyon tuketicileri dinler. */
    public static final String DELIVERY_RESULTS = "hookrelay.delivery-results.v1";

    /**
     * Endpoint konfigurasyonu. COMPACTED topic - her key icin son deger saklanir.
     * Yeni bir dispatcher ayaga kalkinca bu topic'i bastan okuyup tum
     * endpoint'lerin guncel halini ogrenir. admin-api'ye HTTP atmaz.
     */
    public static final String ENDPOINT_CONFIG = "hookrelay.endpoint-config.v1";

    /**
     * Uygulama (musteri) konfigurasyonu. Yine COMPACTED.
     * ingest-api API anahtarini bu replikadan dogrular; her istekte
     * admin-api'ye sormaz. Kontrol duzlemi coktugunde olay kabulu devam eder.
     */
    public static final String APP_CONFIG = "hookrelay.app-config.v1";

    public static int tierCount() {
        return RETRY_TIERS.size();
    }

    /** 1 tabanli katman numarasindan topic adi. */
    public static String retryTier(int tier) {
        if (tier < 1 || tier > RETRY_TIERS.size()) {
            throw new IllegalArgumentException("Gecersiz katman: " + tier);
        }
        return RETRY_TIERS.get(tier - 1);
    }
}
