package com.manish.customagents.runtime.api;

import com.manish.customagents.runtime.model.HumanInteractionResolutionResponse;
import com.manish.customagents.runtime.model.HumanInteractionView;
import com.manish.customagents.runtime.model.SubmitHumanInteractionResponse;
import com.manish.customagents.runtime.service.HumanInteractionService;
import com.manish.customagents.runtime.service.RootHumanResponseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import com.manish.customagents.contracts.AgentApiHeaders;

@Validated
@RestController
@RequestMapping(path = "/api/v1/human-interactions", produces = MediaType.APPLICATION_JSON_VALUE)
public class HumanInteractionController {
    private static final String LICENSE = AgentApiHeaders.LICENSE_CODE;
    private static final String USER = AgentApiHeaders.USER_ID;
    private final HumanInteractionService service;
    private final RootHumanResponseService rootResponses;

    public HumanInteractionController(
            HumanInteractionService service, RootHumanResponseService rootResponses) {
        this.service = service;
        this.rootResponses = rootResponses;
    }

    @GetMapping
    public ResponseEntity<List<HumanInteractionView>> inbox(
            @RequestHeader(LICENSE) @NotBlank String licenseCode,
            @RequestHeader(name = USER, defaultValue = "local-requester") @NotBlank String actorId,
            @RequestHeader(name = AgentApiHeaders.ROLES, defaultValue = "RUN_REQUESTER") String roles,
            Authentication authentication) {
        RuntimeActorContext actor = RuntimeActorContext.resolve(
                authentication, actorId, roles(roles), licenseCode);
        return ResponseEntity.ok(service.inbox(licenseCode, actor.actorId(), actor.roles()));
    }

    @GetMapping("/{interactionId}")
    public ResponseEntity<HumanInteractionView> get(
            @RequestHeader(LICENSE) @NotBlank String licenseCode,
            @RequestHeader(name = USER, defaultValue = "local-requester") @NotBlank String actorId,
            @RequestHeader(name = AgentApiHeaders.ROLES, defaultValue = "RUN_REQUESTER") String roles,
            Authentication authentication,
            @PathVariable @NotBlank @Size(max = 36) String interactionId) {
        RuntimeActorContext actor = RuntimeActorContext.resolve(
                authentication, actorId, roles(roles), licenseCode);
        return ResponseEntity.ok(service.get(
                licenseCode, interactionId, actor.actorId(), actor.roles()));
    }

    @PostMapping(path = "/{interactionId}/responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HumanInteractionResolutionResponse> respond(
            @RequestHeader(LICENSE) @NotBlank String licenseCode,
            @RequestHeader(name = USER, defaultValue = "local-requester") @NotBlank String actorId,
            @RequestHeader(name = AgentApiHeaders.ROLES, defaultValue = "RUN_REQUESTER") String roles,
            Authentication authentication,
            @RequestHeader(name = AgentApiHeaders.IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
            @PathVariable @NotBlank @Size(max = 36) String interactionId,
            @Valid @RequestBody SubmitHumanInteractionResponse command) {
        RuntimeActorContext actor = RuntimeActorContext.resolve(
                authentication, actorId, roles(roles), licenseCode);
        return ResponseEntity.ok(rootResponses.respondSingle(
                licenseCode, interactionId, idempotencyKey,
                actor.actorId(), actor.roles(), command));
    }

    private Set<String> roles(String header) {
        return Arrays.stream(header.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
