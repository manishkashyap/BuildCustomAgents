package com.manish.customagents.error;

import com.manish.customagents.contracts.PromptBudget;

/** Raised when a definition would assemble into a model request larger than the budget allows. */
public class DefinitionBudgetExceededException extends RuntimeException {

    private final transient PromptBudget.Usage usage;

    public DefinitionBudgetExceededException(PromptBudget.Usage usage) {
        super(usage.describe());
        this.usage = usage;
    }

    public int characters() {
        return usage.characters();
    }

    public int limit() {
        return usage.limit();
    }

    public int estimatedTokens() {
        return usage.estimatedTokens();
    }

    public int limitTokens() {
        return usage.limitTokens();
    }
}
