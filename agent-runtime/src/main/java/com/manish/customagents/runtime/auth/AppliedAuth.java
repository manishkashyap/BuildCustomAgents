package com.manish.customagents.runtime.auth;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of applying a credential: the URI to call and the headers to add.
 *
 * <p>A query-parameter credential changes the URI, so the applier returns both rather than only
 * headers. {@link #toString()} is redacted for the same reason as {@link ResolvedCredential}.
 */
public record AppliedAuth(URI uri, Map<String, String> headers) {

    public AppliedAuth {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static AppliedAuth none(URI uri) {
        return new AppliedAuth(uri, Map.of());
    }

    public static AppliedAuth header(URI uri, String name, String value) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(name, value);
        return new AppliedAuth(uri, headers);
    }

    @Override
    public String toString() {
        return "AppliedAuth[uri=" + uri + ", headers=" + headers.keySet() + "]";
    }
}
