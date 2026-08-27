package com.manish.customagents.egress.service;

import com.manish.customagents.contracts.EgressHostRules;

/**
 * Extracts the host from an HTTP tool's URL template.
 *
 * <p>Parsed textually rather than with {@link java.net.URI}, because the template still contains
 * {@code {placeholder}} segments at publish time and would not parse as a URI.
 */
public final class ToolUrlHost {

    private ToolUrlHost() {
    }

    /** The literal host, or null when the template has no usable host. */
    public static String parse(String urlTemplate) {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            return null;
        }
        String remainder = urlTemplate.strip();
        int schemeEnd = remainder.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        remainder = remainder.substring(schemeEnd + 3);

        // Authority runs to the first '/', '?' or '#'.
        int authorityEnd = remainder.length();
        for (int index = 0; index < remainder.length(); index++) {
            char character = remainder.charAt(index);
            if (character == '/' || character == '?' || character == '#') {
                authorityEnd = index;
                break;
            }
        }
        String authority = remainder.substring(0, authorityEnd);

        int userInfo = authority.lastIndexOf('@');
        if (userInfo >= 0) {
            authority = authority.substring(userInfo + 1);
        }
        int portSeparator = authority.lastIndexOf(':');
        if (portSeparator >= 0 && authority.indexOf(']') < portSeparator) {
            authority = authority.substring(0, portSeparator);
        }
        String host = EgressHostRules.normalize(authority);
        return host.isEmpty() ? null : host;
    }

    /**
     * True when the host portion contains a template variable. Such a URL resolves its destination
     * from model-supplied arguments at run time, so the allowlist would be the only thing between a
     * prompt-injected argument and an arbitrary request. Rejected at publish time instead.
     */
    public static boolean hasTemplatedHost(String urlTemplate) {
        String host = parse(urlTemplate);
        return host != null && (host.indexOf('{') >= 0 || host.indexOf('}') >= 0);
    }
}
