package com.manish.customagents.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.manish.customagents.agent.entity.CustomAgentEntity;
import com.manish.customagents.agent.enums.AgentStatus;
import com.manish.customagents.agent.model.CreateCustomAgentRequest;
import com.manish.customagents.agent.model.CopyCustomAgentRequest;
import com.manish.customagents.agent.model.RetirementEligibilityResponse;
import com.manish.customagents.agent.repository.CustomAgentRepository;
import com.manish.customagents.agent.repository.AgentAuditEventRepository;
import com.manish.customagents.agent.repository.AgentCopyRequestRepository;
import com.manish.customagents.tool.repository.CustomToolRepository;
import com.manish.customagents.error.AgentNotFoundException;
import com.manish.customagents.error.DuplicateAgentNameException;
import com.manish.customagents.error.InvalidAgentStatusTransitionException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.validation.Validation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class CustomAgentServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    @Mock
    private CustomAgentRepository repository;
    @Mock private CustomToolRepository toolRepository;
    @Mock private AgentCopyRequestRepository copyRepository;
    @Mock private AgentAuditEventRepository auditRepository;
    @Mock private RuntimeRetirementClient runtimeRetirementClient;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private CustomAgentService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        org.mockito.Mockito.lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        service = new CustomAgentService(
                repository,
                toolRepository,
                copyRepository,
                auditRepository,
                new AgentDefinitionJsonMapper(objectMapper),
                runtimeRetirementClient,
                objectMapper,
                Validation.buildDefaultValidatorFactory().getValidator(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionManager);
    }

    @Test
    void createsVersionOneDraftForTenant() {
        CreateCustomAgentRequest request = request(" Campaign QA Agent ");
        when(repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(
                "account-123", "campaign qa agent")).thenReturn(false);
        when(repository.saveAndFlush(any(CustomAgentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(" account-123 ", request, "user-1", null);

        ArgumentCaptor<CustomAgentEntity> entityCaptor = ArgumentCaptor.forClass(CustomAgentEntity.class);
        verify(repository).saveAndFlush(entityCaptor.capture());
        CustomAgentEntity saved = entityCaptor.getValue();
        assertThat(saved.getLicenseCode()).isEqualTo("account-123");
        assertThat(saved.getName()).isEqualTo("Campaign QA Agent");
        assertThat(saved.getNormalizedName()).isEqualTo("campaign qa agent");
        assertThat(saved.getDefinitionJson()).contains("campaign.get");
        assertThat(saved.getDefinitionJson()).doesNotContain("inputSchema");
        Assertions.assertThat(saved.getStatus()).isEqualTo(AgentStatus.DRAFT);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);

        Assertions.assertThat(response.status()).isEqualTo(AgentStatus.DRAFT);
        Assertions.assertThat(response.version()).isEqualTo(1);
        Assertions.assertThat(response.licenseCode()).isEqualTo("account-123");
        Assertions.assertThat(response.definition().name()).isEqualTo("Campaign QA Agent");
    }

    @Test
    void rejectsDuplicateActiveNameWithinTenant() {
        CreateCustomAgentRequest request = request("Campaign QA Agent");
        when(repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(
                "account-123", "campaign qa agent")).thenReturn(true);

        assertThatThrownBy(() -> service.create("account-123", request, "user-1", null))
                .isInstanceOf(DuplicateAgentNameException.class)
                .hasMessageContaining("Campaign QA Agent");
    }

    @Test
    void partiallyUpdatesADraftWithoutChangingItsBusinessVersion() {
        CustomAgentEntity entity = entity(AgentStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(
                entity.getId(), "account-123")).thenReturn(Optional.of(entity));
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        var response = service.updateDraft("account-123", entity.getId(),
                JsonNodeFactory.instance.objectNode().put("role", "Updated analyst"),
                "user-1", "Tune the role");

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.definition().role()).isEqualTo("Updated analyst");
        assertThat(response.definition().instructions()).isEqualTo("Review campaign");
    }

    @Test
    void rejectsEditingAPublishedAgent() {
        CustomAgentEntity entity = entity(AgentStatus.PUBLISHED);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(
                entity.getId(), "account-123")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateDraft("account-123", entity.getId(),
                JsonNodeFactory.instance.objectNode().put("role", "Updated analyst"),
                "user-1", null))
                .isInstanceOf(InvalidAgentStatusTransitionException.class);
    }

    @Test
    void copiesAPublishedAgentAsAnIndependentVersionOneDraft() {
        CustomAgentEntity source = entity(AgentStatus.PUBLISHED);
        when(copyRepository.findByLicenseCodeAndIdempotencyKey("account-123", "copy-key"))
                .thenReturn(Optional.empty());
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(
                source.getId(), "account-123")).thenReturn(Optional.of(source));
        when(repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(
                "account-123", "campaign qa agent v2")).thenReturn(false);
        when(repository.saveAndFlush(any(CustomAgentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.copy("account-123", source.getId(),
                new CopyCustomAgentRequest("Campaign QA Agent v2"), "copy-key", "user-1", null);

        assertThat(response.id()).isNotEqualTo(source.getId());
        assertThat(response.status()).isEqualTo(AgentStatus.DRAFT);
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.definition().name()).isEqualTo("Campaign QA Agent v2");
    }

    @Test
    void retiresOnlyAfterRuntimeReportsNoActiveRuns() {
        CustomAgentEntity entity = entity(AgentStatus.PUBLISHED);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(
                entity.getId(), "account-123")).thenReturn(Optional.of(entity));
        when(repository.saveAndFlush(entity)).thenReturn(entity);
        when(runtimeRetirementClient.check("account-123", entity.getId()))
                .thenReturn(new RetirementEligibilityResponse(true, 0, java.util.Map.of()));

        var response = service.updateStatus(
                "account-123", entity.getId(), AgentStatus.RETIRED, "user-1", null);

        assertThat(response.status()).isEqualTo(AgentStatus.RETIRED);
        verify(runtimeRetirementClient).check("account-123", entity.getId());
    }

    @Test
    void publishesDraftAgentWithinTenant() {
        CustomAgentEntity entity = entity(AgentStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(
                entity.getId(), "account-123")).thenReturn(Optional.of(entity));
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        var response = service.updateStatus(
                " account-123 ", entity.getId(), AgentStatus.PUBLISHED, "user-1", null);

        Assertions.assertThat(entity.getStatus()).isEqualTo(AgentStatus.PUBLISHED);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        Assertions.assertThat(response.status()).isEqualTo(AgentStatus.PUBLISHED);
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        verify(repository).saveAndFlush(entity);
    }

    @Test
    void treatsPublishingAnAlreadyPublishedAgentAsIdempotent() {
        CustomAgentEntity entity = entity(AgentStatus.PUBLISHED);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(
                entity.getId(), "account-123")).thenReturn(Optional.of(entity));

        var response = service.updateStatus(
                "account-123", entity.getId(), AgentStatus.PUBLISHED, "user-1", null);

        Assertions.assertThat(response.status()).isEqualTo(AgentStatus.PUBLISHED);
        verify(repository, never()).saveAndFlush(any(CustomAgentEntity.class));
    }

    @Test
    void rejectsPublishingARetiredAgent() {
        CustomAgentEntity entity = entity(AgentStatus.RETIRED);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(
                entity.getId(), "account-123")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateStatus(
                "account-123", entity.getId(), AgentStatus.PUBLISHED, "user-1", null))
                .isInstanceOf(InvalidAgentStatusTransitionException.class)
                .hasMessageContaining("RETIRED to PUBLISHED");

        verify(repository, never()).saveAndFlush(any(CustomAgentEntity.class));
    }

    @Test
    void doesNotExposeAnAgentFromAnotherTenant() {
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(
                "agent-123", "account-123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(
                "account-123", "agent-123", AgentStatus.PUBLISHED, "user-1", null))
                .isInstanceOf(AgentNotFoundException.class)
                .hasMessageContaining("agent-123");
    }

    private CustomAgentEntity entity(AgentStatus status) {
        Instant createdAt = NOW.minusSeconds(3600);
        return new CustomAgentEntity(
                "d272ef82-c734-4873-9346-b4d250f8bf43",
                "account-123",
                "Campaign QA Agent",
                "campaign qa agent",
                "Checks campaign readiness",
                definitionJson(),
                status,
                1,
                false,
                createdAt,
                createdAt);
    }

    private String definitionJson() {
        try {
            return new ObjectMapper().writeValueAsString(new CreateCustomAgentRequest(
                    "Campaign QA Agent", "Checks campaign readiness", "Analyst", "Review campaign",
                    List.of(), null, null, JsonNodeFactory.instance.objectNode(), List.of(), Set.of(), null));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private CreateCustomAgentRequest request(String name) {
        return new CreateCustomAgentRequest(
                name,
                "Checks campaign readiness",
                "Campaign quality analyst",
                "Review the supplied campaign.",
                List.of("Do not mutate campaigns"),
                "JSON",
                null,
                JsonNodeFactory.instance.objectNode(),
                List.of(),
                Set.of("campaign.get"),
                null);
    }
}
