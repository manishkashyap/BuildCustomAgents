package com.manish.customagents.runtime.model;

public record AttachmentReference(String reference, String mediaType, String fileName) {

    public AttachmentReference {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("attachment reference must not be blank");
        }
        reference = reference.strip();
        mediaType = normalizeNullable(mediaType);
        fileName = normalizeNullable(fileName);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
