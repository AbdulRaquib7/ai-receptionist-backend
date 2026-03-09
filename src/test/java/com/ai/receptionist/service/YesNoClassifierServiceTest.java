package com.ai.receptionist.service;

import com.ai.receptionist.utils.YesNoResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class YesNoClassifierServiceTest {

    private final YesNoClassifierService service = new YesNoClassifierService();

    @ParameterizedTest
    @ValueSource(strings = {"yes", "yeah", "yep", "ya", "yup", "ok", "okay", "sure", "correct", "right", "confirm", "confirmed", "go ahead", "please do", "do it"})
    void shouldClassifyAffirmative(String input) {
        assertThat(service.classify(input)).isEqualTo(YesNoResult.YES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no", "nope", "nah", "cancel", "stop", "never mind", "nevermind", "dont", "don't", "wait", "not now", "not yet", "hold on"})
    void shouldClassifyNegative(String input) {
        assertThat(service.classify(input)).isEqualTo(YesNoResult.NO);
    }

    @Test
    void shouldClassifyUnknown() {
        assertThat(service.classify("maybe")).isEqualTo(YesNoResult.UNKNOWN);
        assertThat(service.classify("")).isEqualTo(YesNoResult.UNKNOWN);
        assertThat(service.classify(null)).isEqualTo(YesNoResult.UNKNOWN);
    }

    @Test
    void shouldDetectYesAndNoInSentence() {
        assertThat(service.classify("Yes, please do.")).isEqualTo(YesNoResult.YES);
        assertThat(service.classify("No, not now.")).isEqualTo(YesNoResult.NO);
    }

    @Test
    void shouldHandleMixedInputAsUnknown() {
        // Both yes and no patterns found
        assertThat(service.classify("Yes but no.")).isEqualTo(YesNoResult.UNKNOWN);
    }
}
