package com.manish.customagents.contracts;

import java.util.Set;

/**
 * The tool types the runtime can actually execute.
 *
 * <p>Management consults this when publishing so a definition naming a type with no executor is
 * rejected at publish time rather than failing mid-run, once the model has already spent turns.
 * The runtime's {@code ToolExecutorRegistry} is the authority; a test asserts the two agree, so
 * registering an executor without listing it here fails the build.
 */
public final class ExecutableToolTypes {

    private static final Set<ToolType> SUPPORTED =
            Set.of(ToolType.HTTP, ToolType.BUILT_IN, ToolType.CUSTOM_AGENT);

    private ExecutableToolTypes() {
    }

    public static Set<ToolType> supported() {
        return SUPPORTED;
    }

    public static boolean isExecutable(ToolType type) {
        return type != null && SUPPORTED.contains(type);
    }
}
