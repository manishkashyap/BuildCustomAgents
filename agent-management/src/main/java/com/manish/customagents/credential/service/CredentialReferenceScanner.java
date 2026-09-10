package com.manish.customagents.credential.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.contracts.ToolAuthRules;
import com.manish.customagents.tool.entity.CustomToolEntity;
import com.manish.customagents.tool.repository.CustomToolRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/** Finds which of a tenant's tools reference a credential by name. */
@Component
public class CredentialReferenceScanner {

    private final CustomToolRepository toolRepository;
    private final ObjectMapper objectMapper;

    public CredentialReferenceScanner(CustomToolRepository toolRepository, ObjectMapper objectMapper) {
        this.toolRepository = toolRepository;
        this.objectMapper = objectMapper;
    }

    public List<String> toolsReferencing(String licenseCode, String credentialName) {
        String wanted = ToolAuthRules.normalizeName(credentialName);
        return toolRepository.findByLicenseCodeAndDeletedFalseOrderByUpdatedAtDesc(licenseCode).stream()
                .filter(tool -> wanted.equals(credentialNameOf(tool)))
                .map(CustomToolEntity::getName)
                .toList();
    }

    /** The credential a tool's configuration references, or null when it needs no authentication. */
    public String credentialNameOf(CustomToolEntity tool) {
        try {
            JsonNode configuration = objectMapper.readTree(tool.getDefinitionJson()).path("configuration");
            return credentialNameOf(configuration);
        } catch (Exception exception) {
            // A tool whose stored definition will not parse is a separate problem; it references nothing.
            return null;
        }
    }

    public String credentialNameOf(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) {
            return null;
        }
        JsonNode auth = configuration.path("auth");
        if (!auth.isObject()) {
            return null;
        }
        String name = auth.path(ToolAuthRules.CREDENTIAL_KEY).asText("").strip();
        return name.isEmpty() ? null : name;
    }
}
