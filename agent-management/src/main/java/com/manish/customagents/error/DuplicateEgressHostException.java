package com.manish.customagents.error;

public class DuplicateEgressHostException extends RuntimeException {
    public DuplicateEgressHostException(String hostPattern) {
        super("Egress host pattern already registered: " + hostPattern);
    }
}
