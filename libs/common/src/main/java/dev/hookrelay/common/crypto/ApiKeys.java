package dev.hookrelay.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * API anahtari uretimi ve hash'lenmesi.
 *
 * Anahtarin kendisi HICBIR YERDE saklanmaz - ne veritabaninda, ne Kafka'da,
 * ne logda. Sadece SHA-256 hash'i saklanir. Kullaniciya duz metin bir kez,
 * olusturma cevabinda gosterilir. Kaybederse yenisini uretiriz.
 *
 * Neden bcrypt degil SHA-256: bu bir kullanici parolasi degil, 256 bitlik
 * rastgele bir dizge. Sozluk saldirisi yapilamayacagi icin yavaslatmaya
 * (bcrypt/argon2) gerek yok; her istekte dogrulanacagi icin de hizli olmali.
 */
public final class ApiKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final String PREFIX = "hr_";

    private ApiKeys() {}

    public static String generate() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return PREFIX + HEX.formatHex(raw);
    }

    public static String hash(String apiKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(md.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("API anahtari hash'lenemedi", e);
        }
    }

    /** Arayuzde "hr_3f9a..." diye gosterebilmek icin. Tek basina ise yaramaz. */
    public static String preview(String apiKey) {
        return apiKey.length() <= 10 ? apiKey : apiKey.substring(0, 10) + "...";
    }
}
