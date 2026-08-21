package dev.hookrelay.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignerTest {

    private final WebhookSigner signer = new WebhookSigner();
    private static final String SECRET = "whsec_test_0123456789abcdef";
    private static final String PAYLOAD = "{\"eventType\":\"order.paid\",\"amount\":149.90}";

    @Test
    @DisplayName("Uretilen imza kendi dogrulamasindan gecer")
    void imza_dogrulanir() {
        String header = signer.sign(SECRET, PAYLOAD, Instant.now());
        assertThat(signer.verify(SECRET, PAYLOAD, header, 300)).isTrue();
    }

    @Test
    @DisplayName("Imza bicimi t=<ts>,v1=<hex>")
    void imza_bicimi() {
        String header = signer.sign(SECRET, PAYLOAD, Instant.ofEpochSecond(1_700_000_000L));
        assertThat(header).startsWith("t=1700000000,v1=");
        assertThat(header.split("v1=")[1]).hasSize(64);   // SHA-256 = 32 bayt = 64 hex
    }

    @Test
    @DisplayName("Govde degisirse imza tutmaz")
    void govde_kurcalanirsa_reddedilir() {
        String header = signer.sign(SECRET, PAYLOAD, Instant.now());
        String tampered = PAYLOAD.replace("149.90", "1.00");
        assertThat(signer.verify(SECRET, tampered, header, 300)).isFalse();
    }

    @Test
    @DisplayName("Yanlis sir ile dogrulama basarisiz")
    void yanlis_sir_reddedilir() {
        String header = signer.sign(SECRET, PAYLOAD, Instant.now());
        assertThat(signer.verify("whsec_baska_sir", PAYLOAD, header, 300)).isFalse();
    }

    @Test
    @DisplayName("Eski imza tolerans disinda reddedilir - replay saldirisi kapali")
    void eski_imza_reddedilir() {
        Instant tenMinutesAgo = Instant.now().minusSeconds(600);
        String header = signer.sign(SECRET, PAYLOAD, tenMinutesAgo);

        assertThat(signer.verify(SECRET, PAYLOAD, header, 300)).isFalse();   // 5 dk tolerans
        assertThat(signer.verify(SECRET, PAYLOAD, header, 900)).isTrue();    // 15 dk tolerans
    }

    @Test
    @DisplayName("Zaman damgasi kurcalanirsa imza tutmaz")
    void zaman_damgasi_kurcalanirsa_reddedilir() {
        // Gercek replay senaryosu: saldirgan bir saat once yakaladigi
        // gecerli istegi tekrar gondermek istiyor. Eskidigi icin
        // tolerans kontrolune takilacak; o yuzden t= degerini
        // guncelliyor. Ama v1 ESKI t'ye gore hesaplanmisti - tutmaz.
        String header = signer.sign(SECRET, PAYLOAD, Instant.now().minusSeconds(3600));
        String forged = header.replaceFirst("t=\\d+", "t=" + Instant.now().getEpochSecond());
        assertThat(signer.verify(SECRET, PAYLOAD, forged, 300))
                .as("t imzanin icinde oldugu icin tek basina degistirilemez")
                .isFalse();
    }

    @Test
    @DisplayName("Bozuk basliklar cokmeden reddedilir")
    void bozuk_baslik_reddedilir() {
        assertThat(signer.verify(SECRET, PAYLOAD, null, 300)).isFalse();
        assertThat(signer.verify(SECRET, PAYLOAD, "", 300)).isFalse();
        assertThat(signer.verify(SECRET, PAYLOAD, "sacmalik", 300)).isFalse();
        assertThat(signer.verify(SECRET, PAYLOAD, "t=abc,v1=xyz", 300)).isFalse();
        assertThat(signer.verify(SECRET, PAYLOAD, "v1=deadbeef", 300)).isFalse();
        assertThat(signer.verify(SECRET, PAYLOAD, "t=1700000000,v1=ZZZZ", 300)).isFalse();
    }

    @Test
    @DisplayName("API anahtari hash'i belirlenimci ve anahtari sizdirmiyor")
    void api_anahtari_hash() {
        String key = ApiKeys.generate();
        assertThat(key).startsWith("hr_").hasSize(51);           // hr_ + 48 hex
        assertThat(ApiKeys.hash(key)).isEqualTo(ApiKeys.hash(key)).hasSize(64);
        assertThat(ApiKeys.hash(key)).doesNotContain(key.substring(3));
        assertThat(ApiKeys.preview(key)).hasSize(13).startsWith("hr_").endsWith("...");
    }
}
