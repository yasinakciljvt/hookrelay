package dev.hookrelay.common.error;

import java.time.Instant;
import java.util.List;

/**
 * Tek hata sozlesmesi. Butun servisler ayni govdeyi doner, istemci
 * tarafinda tek bir hata ayristirici yeter.
 */
public record ErrorResponse(
        String code,
        String message,
        List<String> details,
        String path,
        Instant timestamp
) {
    public static ErrorResponse of(String code, String message, String path) {
        return new ErrorResponse(code, message, List.of(), path, Instant.now());
    }

    public static ErrorResponse of(String code, String message, List<String> details, String path) {
        return new ErrorResponse(code, message, details, path, Instant.now());
    }
}
