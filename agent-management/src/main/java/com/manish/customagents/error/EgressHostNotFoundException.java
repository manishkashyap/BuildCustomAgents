package com.manish.customagents.error;

public class EgressHostNotFoundException extends RuntimeException {
    public EgressHostNotFoundException(String id) {
        super("Egress host not found: " + id);
    }
}
