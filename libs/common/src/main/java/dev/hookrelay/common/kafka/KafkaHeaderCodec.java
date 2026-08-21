package dev.hookrelay.common.kafka;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;

/**
 * Kafka basliklari ham byte[]. Her yerde ayni donusumu elle yazmamak icin.
 */
public final class KafkaHeaderCodec {

    private KafkaHeaderCodec() {}

    public static byte[] of(long value) {
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] of(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    public static String string(Headers headers, String key, String fallback) {
        Header h = headers.lastHeader(key);
        if (h == null || h.value() == null) return fallback;
        return new String(h.value(), StandardCharsets.UTF_8);
    }

    public static long longValue(Headers headers, String key, long fallback) {
        String s = string(headers, key, null);
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int intValue(Headers headers, String key, int fallback) {
        return (int) longValue(headers, key, fallback);
    }
}
