package com.manish.customagents.contracts;

/**
 * The size a definition may reach before it stops fitting in a model request.
 *
 * <p>Per-field limits bound what an author can type into one box. They do not bound what
 * reaches the model, because the fields with the largest prompt impact — an agent's
 * {@code context} and {@code examples}, a tool's {@code inputSchema} — are free-form JSON
 * with no length constraint at all. An agent can therefore pass every field validation and
 * still assemble a request no model will accept, and it fails at run time, per run, after
 * the definition has already been published.
 *
 * <p>These budgets close that gap at publish time. They are deliberately generous: the point
 * is to catch a definition that has grown by an order of magnitude, not to second-guess an
 * author who needs a long tool description.
 *
 * <p>Only model-facing content counts. A tool's {@code configuration} and
 * {@code executionPolicy} are read by the runtime and never sent to a provider, so they are
 * excluded — charging an author for an HTTP URL they cannot shorten would be noise.
 *
 * @see HumanInteractionPolicyRules for the other shared bounds both services enforce
 */
public final class PromptBudget {

    /** Rough characters-per-token for English prose and JSON. Good to about 20%. */
    public static final int CHARACTERS_PER_TOKEN = 4;

    /** One tool: description, input schema and output schema. */
    public static final int MAX_TOOL_CHARACTERS = 8_000;

    /** One agent: every field that reaches the provider, including the response schema. */
    public static final int MAX_AGENT_CHARACTERS = 60_000;

    /** An agent plus every tool it allows — what a single turn actually carries. */
    public static final int MAX_ASSEMBLED_CHARACTERS = 120_000;

    private PromptBudget() {
    }

    /**
     * How much of a budget something uses.
     *
     * @param subject what was measured, for the error message
     * @param characters model-facing characters counted
     * @param limit the applicable budget
     */
    public record Usage(String subject, int characters, int limit) {

        public boolean exceeded() {
            return characters > limit;
        }

        public int estimatedTokens() {
            return estimateTokens(characters);
        }

        public int limitTokens() {
            return estimateTokens(limit);
        }

        /** A message naming the overage in both characters and estimated tokens. */
        public String describe() {
            return subject + " is " + characters + " characters (about " + estimatedTokens()
                    + " tokens), over the " + limit + " character budget (about " + limitTokens()
                    + " tokens)";
        }
    }

    public static int estimateTokens(int characters) {
        return (characters + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN;
    }

    public static Usage forTool(String toolName, int characters) {
        return new Usage("Tool " + toolName, characters, MAX_TOOL_CHARACTERS);
    }

    public static Usage forAgent(String agentName, int characters) {
        return new Usage("Agent " + agentName, characters, MAX_AGENT_CHARACTERS);
    }

    public static Usage forAssembledPrompt(String agentName, int characters) {
        return new Usage(
                "Agent " + agentName + " with its allowed tools", characters, MAX_ASSEMBLED_CHARACTERS);
    }
}
