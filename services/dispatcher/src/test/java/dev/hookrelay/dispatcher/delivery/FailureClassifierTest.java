package dev.hookrelay.dispatcher.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    private final FailureClassifier strict  = new FailureClassifier(false);  // varsayilan
    private final FailureClassifier lenient = new FailureClassifier(true);   // 4xx'i de dene

    private SendResult status(Integer code) {
        return new SendResult(code, 12, "ok", null, null);
    }

    @ParameterizedTest(name = "HTTP {0} → basarili")
    @ValueSource(ints = {200, 201, 202, 204, 299})
    void ikiyuzler_basarili(int code) {
        assertThat(status(code).success()).isTrue();
        assertThat(strict.permanent(status(code))).isFalse();
    }

    @ParameterizedTest(name = "HTTP {0} → gecici, yeniden denenir")
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void gecici_hatalar(int code) {
        assertThat(status(code).success()).isFalse();
        assertThat(strict.permanent(status(code)))
                .as("408 ve 429 'simdi olmaz' demek, 'asla' degil")
                .isFalse();
    }

    @Test
    @DisplayName("Ag hatasi (kod yok) gecici sayilir")
    void ag_hatasi_gecici() {
        SendResult r = new SendResult(null, 5000, null, "Connection refused", null);
        assertThat(r.success()).isFalse();
        assertThat(strict.permanent(r))
                .as("baglanti kurulamadi - sunucu yarin ayakta olabilir")
                .isFalse();
    }

    @Nested
    @DisplayName("Varsayilan ayar (retry-on-4xx = false)")
    class Varsayilan {

        @ParameterizedTest(name = "HTTP {0} → KALICI, tek denemede DLQ")
        @ValueSource(ints = {400, 401, 403, 404, 422})
        void dortyuzler_kalici(int code) {
            assertThat(strict.permanent(status(code)))
                    .as("ayni istegi tekrar gondermek ayni cevabi alir")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Ayar acikken (retry-on-4xx = true)")
    class AyarAcik {

        @ParameterizedTest(name = "HTTP {0} → artik gecici, yeniden denenir")
        @ValueSource(ints = {400, 401, 403, 404, 422})
        void dortyuzler_yeniden_denenir(int code) {
            assertThat(lenient.permanent(status(code)))
                    .as("token yenilerken gecici 403 donen musteriler icin")
                    .isFalse();
        }

        @Test
        @DisplayName("410 Gone AYARDAN ETKILENMEZ - her zaman kalici")
        void gone_her_zaman_kalici() {
            assertThat(strict.permanent(status(410))).isTrue();
            assertThat(lenient.permanent(status(410)))
                    .as("'bu kaynak kalici olarak yok' demenin baska yolu yok; israr etmek kabalik")
                    .isTrue();
        }
    }
}
