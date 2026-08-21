package dev.hookrelay.ingestapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hookrelay.common.crypto.ApiKeys;
import dev.hookrelay.common.error.ErrorResponse;
import dev.hookrelay.common.redis.AppConfigCache;
import dev.hookrelay.contracts.ApplicationConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * API anahtari dogrulamasi.
 *
 * TASARIMIN CAN ALICI NOKTASI: dogrulama Redis replikasindan yapiliyor,
 * admin-api'ye HTTP atilmiyor. Sonuc:
 *   - Sicak yolda ag cagrisi yok, gecikme ~0.2 ms.
 *   - admin-api tamamen coktugunde bile olay kabulu CALISMAYA DEVAM EDER.
 *
 * Bedeli: anahtar iptal edildiginde replikaya ulasmasi birkac yuz
 * milisaniye surer. Bilincli bir takas - kabul edilen olay zaten
 * musterinin kendi olayi, saniyelik gecikme guvenlik acigi degil.
 *
 * Neden Spring Security degil: burada rol, oturum, CSRF, form login yok.
 * Tek bir bearer token karsilastirmasi icin Spring Security kurmak,
 * projeye anlamadigin 200 satirlik bir filtre zinciri sokmak demek.
 * (admin-api ileride gercek kullanici girisi isterse orada kurulur.)
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String ATTR_APPLICATION_ID = "hookrelay.applicationId";

    private final AppConfigCache apps;
    private final ObjectMapper mapper;

    public ApiKeyFilter(AppConfigCache apps, ObjectMapper mapper) {
        this.apps = apps;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            reject(request, response, "Authorization: Bearer <api-key> basligi gerekli");
            return;
        }

        String apiKey = header.substring("Bearer ".length()).trim();
        Optional<ApplicationConfig> app = apps.findByApiKeyHash(ApiKeys.hash(apiKey));

        if (app.isEmpty()) {
            reject(request, response, "API anahtari gecersiz");
            return;
        }
        if (!app.get().enabled()) {
            reject(request, response, "Uygulama devre disi");
            return;
        }

        request.setAttribute(ATTR_APPLICATION_ID, app.get().applicationId());
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(),
                ErrorResponse.of("UNAUTHORIZED", message, request.getRequestURI()));
    }
}
