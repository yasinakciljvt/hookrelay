package dev.hookrelay.common.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Giden webhook'lari imzalar, gelenleri dogrular.
 *
 * Bicim (Stripe'in kullandigi sema):
 *   imzalanan metin = "{timestamp}.{govde}"
 *   baslik          = "t=1723459200,v1=<hex-hmac-sha256>"
 *
 * Zaman damgasi neden imzaya dahil:
 * Sadece govde imzalansaydi, araya giren biri gecerli bir istegi kaydedip
 * yarin tekrar gonderebilirdi - imza hala gecerli olurdu. Zaman damgasi
 * imzanin icinde oldugu icin degistirilemez, alici da "5 dakikadan eskisini
 * kabul etme" diyerek replay penceresini kapatir.
 *
 * v1 oneki neden var: yarin SHA-256'dan vazgecmek gerekirse v2 eklenip
 * gecis suresince iki imza birden gonderilebilir. Musteriler kirilmaz.
 */
@Component
public class WebhookSigner {

    private static final String ALGO = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    public String sign(String secret, String payload, Instant timestamp) {
        long ts = timestamp.getEpochSecond();
        String signed = ts + "." + payload;
        return "t=" + ts + ",v1=" + HEX.formatHex(hmac(secret, signed));
    }

    /**
     * Alici tarafin yapmasi gereken dogrulama. chaos-target bunu kullaniyor,
     * yani projede imzanin gercekten dogrulandigi calisir bir ornek var.
     */
    public boolean verify(String secret, String payload, String headerValue, long toleranceSeconds) {
        if (headerValue == null || headerValue.isBlank()) return false;

        long ts = -1;
        String v1 = null;
        for (String part : headerValue.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            switch (kv[0].trim()) {
                case "t" -> {
                    try { ts = Long.parseLong(kv[1].trim()); } catch (NumberFormatException ignored) { }
                }
                case "v1" -> v1 = kv[1].trim();
            }
        }
        if (ts < 0 || v1 == null) return false;

        long age = Math.abs(Instant.now().getEpochSecond() - ts);
        if (age > toleranceSeconds) return false;

        byte[] expected = hmac(secret, ts + "." + payload);
        byte[] actual;
        try {
            actual = HEX.parseHex(v1);
        } catch (IllegalArgumentException e) {
            return false;
        }
        // Sabit zamanli karsilastirma: String.equals erken cikar ve
        // baytlarin kacinin tuttugunu zamanlamayla sizdirir.
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC uretilemedi", e);
        }
    }
}
