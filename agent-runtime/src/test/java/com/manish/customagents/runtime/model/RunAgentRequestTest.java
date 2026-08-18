package com.manish.customagents.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class RunAgentRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsProviderAndModelTogether() {
        var request = request(ModelProvider.OPENAI, "gpt-customer");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void acceptsOmittedProviderAndModelForDefaultSelection() {
        var request = request(null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsPartialOrBlankModelSelection() {
        assertThat(validator.validate(request(ModelProvider.OPENAI, null))).isNotEmpty();
        assertThat(validator.validate(request(null, "gpt-customer"))).isNotEmpty();
        assertThat(validator.validate(request(ModelProvider.OPENAI, "   "))).isNotEmpty();
    }

    private RunAgentRequest request(ModelProvider provider, String model) {
        return new RunAgentRequest(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "Run the agent",
                JsonNodeFactory.instance.objectNode(),
                provider,
                model);
    }
}
