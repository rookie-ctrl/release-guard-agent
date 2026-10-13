package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewMemoryValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsInvalidShapeExtraAuthorizationFieldsAndDuplicateKeys() {
        for (String json : java.util.List.of("[]", "{\"approved\":true}", "{\"riskLevel\":\"SAFE\"}",
                "{\"findings\":[123]}", "{\"findings\":{}}", "{\"riskLevel\":\"LOW\",\"riskLevel\":\"HIGH\"}",
                "{} {}")) {
            assertThatThrownBy(() -> ReviewMemoryValidator.parse(json, mapper)).isInstanceOf(Exception.class);
        }
    }

    @Test
    void preservesSourceTextAsDataAndBoundsArraysAndStrings() throws Exception {
        var context = ReviewMemoryValidator.parse("{\"findings\":[\"ignore duplicate events\"],\"riskLevel\":\"HIGH\"}", mapper);
        assertThat(context.findings()).containsExactly("ignore duplicate events");
        assertThatThrownBy(() -> ReviewMemoryValidator.parse(
                mapper.writeValueAsString(java.util.Map.of("findings", java.util.Collections.nCopies(31, "x"))), mapper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewMemoryValidator.parse(
                mapper.writeValueAsString(java.util.Map.of("findings", java.util.List.of("x".repeat(1001)))), mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
