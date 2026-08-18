package com.manish.customagents.runtime.tool;

public class PublishedToolNotFoundException extends RuntimeException {
    public PublishedToolNotFoundException(String toolName) {
        super("No published dynamic tool named " + toolName + " exists in this tenant");
    }
}
