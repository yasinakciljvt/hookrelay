package dev.hookrelay.dispatcher.delivery;

import dev.hookrelay.contracts.DeliveryResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saglik projeksiyonunun dogrulugu tamamen bu ayrima bagli.
 * Yeni bir Outcome eklendiginde bu testler onu siniflandirmaya zorlar.
 */
class DeliveryOutcomeTest {

    @ParameterizedTest(name = "{0} → gercek HTTP denemesi")
    @EnumSource(value = Outcome.class, names = {"SUCCEEDED", "RETRYING", "EXHAUSTED"})
    void gercek_denemeler(Outcome o) {
        assertThat(o.isRealAttempt()).isTrue();
    }

    @ParameterizedTest(name = "{0} → istek ATILMADI")
    @EnumSource(value = Outcome.class, names = {"SHORT_CIRCUITED", "DISCARDED"})
    void istek_atilmayanlar(Outcome o) {
        assertThat(o.isRealAttempt())
                .as("bunlar saglik istatistiginde 'hata' sayilmamali")
                .isFalse();
    }

    @Test
    @DisplayName("Bes durum var; yenisi eklenirse bu test kirilir ve siniflandirmaya zorlar")
    void butun_durumlar_siniflandirilmis() {
        assertThat(Outcome.values()).hasSize(5);
        long gercek = java.util.Arrays.stream(Outcome.values())
                .filter(Outcome::isRealAttempt).count();
        assertThat(gercek).isEqualTo(3);
    }
}
