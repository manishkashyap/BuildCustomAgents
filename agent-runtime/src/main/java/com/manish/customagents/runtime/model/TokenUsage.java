package com.manish.customagents.runtime.model;

public record TokenUsage(int inputTokens, int outputTokens, int totalTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token usage values cannot be negative");
        }
        if (totalTokens < inputTokens + outputTokens) {
            throw new IllegalArgumentException("totalTokens cannot be less than input plus output tokens");
        }
    }

    public static TokenUsage unknown() {
        return new TokenUsage(0, 0, 0);
    }
}
