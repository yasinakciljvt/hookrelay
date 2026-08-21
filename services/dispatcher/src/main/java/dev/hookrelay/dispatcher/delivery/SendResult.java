package dev.hookrelay.dispatcher.delivery;

import java.time.Duration;

/**
 * Tek bir HTTP denemesinin ham sonucu.
 *
 * @param retryAfter sunucunun Retry-After basligiyla soyledigi bekleme suresi
 */
public record SendResult(
        Integer httpStatus,
        long latencyMs,
        String responseSnippet,
        String error,
        Duration retryAfter
) {
    public boolean success() {
        return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }

    // Not: "bu hata kalici mi" sorusu bilincli olarak burada DEGIL.
    // O bir politika karari ve konfigurasyona bagli -- bkz. FailureClassifier.
    // Bu kayit sadece ne oldugunu tasir, ne yapilacagini degil.
}
