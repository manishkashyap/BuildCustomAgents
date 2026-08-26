package com.manish.customagents.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.manish.customagents.agent.entity.AgentVersionEntity;
import com.manish.customagents.agent.entity.CustomAgentEntity;
import com.manish.customagents.agent.enums.AgentLineageStatus;
import com.manish.customagents.agent.enums.AgentStatus;
import com.manish.customagents.agent.enums.AgentVersionStatus;
import com.manish.customagents.agent.model.CopyCustomAgentRequest;
import com.manish.customagents.agent.model.CreateCustomAgentRequest;
import com.manish.customagents.contracts.RetirementEligibilityResponse;
import com.manish.customagents.agent.repository.AgentAuditEventRepository;
import com.manish.customagents.agent.repository.AgentCopyRequestRepository;
import com.manish.customagents.agent.repository.AgentVersionRepository;
import com.manish.customagents.agent.repository.CustomAgentRepository;
import com.manish.customagents.error.AgentNotFoundException;
import com.manish.customagents.error.DuplicateAgentNameException;
import com.manish.customagents.error.InvalidAgentStatusTransitionException;
import com.manish.customagents.budget.DefinitionBudgetValidator;
import com.manish.customagents.tool.repository.CustomToolRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.Validation;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class CustomAgentServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
    private static final String AGENT_ID = "d272ef82-c734-4873-9346-b4d250f8bf43";

    @Mock
    private CustomAgentRepository repository;
    @Mock private AgentVersionRepository versionRepository;
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
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        service = new CustomAgentService(
                repository,
                versionRepository,
                toolRepository,
                copyRepository,
                auditRepository,
                new AgentDefinitionJsonMapper(objectMapper),
                new DefinitionBudgetValidator(objectMapper),
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
        when(versionRepository.saveAndFlush(any(AgentVersionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(" account-123 ", request, "user-1", null);

        ArgumentCaptor<CustomAgentEntity> lineageCaptor = ArgumentCaptor.forClass(CustomAgentEntity.class);
        verify(repository).saveAndFlush(lineageCaptor.capture());
        CustomAgentEntity lineage = lineageCaptor.getValue();
        assertThat(lineage.getLicenseCode()).isEqualTo("account-123");
        assertThat(lineage.getName()).isEqualTo("Campaign QA Agent");
        assertThat(lineage.getNormalizedName()).isEqualTo("campaign qa agent");
        Assertions.assertThat(lineage.getStatus()).isEqualTo(AgentLineageStatus.ACTIVE);
        assertThat(lineage.getDraftVersion()).isEqualTo(1);
        assertThat(lineage.getActiveVersion()).isNull();
        assertThat(lineage.getNextVersion()).isEqualTo(2);
        assertThat(lineage.getCreatedAt()).isEqualTo(NOW);

        ArgumentCaptor<AgentVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(AgentVersionEntity.class);
        verify(versionRepository).saveAndFlush(versionCaptor.capture());
        AgentVersionEntity version = versionCaptor.getValue();
        assertThat(version.getAgentId()).isEqualTo(lineage.getId());
        assertThat(version.getVersion()).isEqualTo(1);
        Assertions.assertThat(version.getStatus()).isEqualTo(AgentVersionStatus.DRAFT);
        assertThat(version.getDefinitionJson()).contains("campaign.get");
        assertThat(version.getDefinitionJson()).doesNotContain("inputSchema");

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
        Fixture fixture = agent(AgentStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(AGENT_ID, "account-123"))
                .thenReturn(Optional.of(fixture.lineage()));
        when(repository.saveAndFlush(fixture.lineage())).thenReturn(fixture.lineage());

        var response = service.updateDraft("account-123", AGENT_ID,
                JsonNodeFactory.instance.objectNode().put("role", "Updated analyst"),
                "user-1", "Tune the role");

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.definition().role()).isEqualTo("Updated analyst");
        assertThat(response.definition().instructions()).isEqualTo("Review campaign");
        Assertions.assertThat(fixture.version().getStatus()).isEqualTo(AgentVersionStatus.DRAFT);
    }

    @Test
    void rejectsEditingAPublishedAgent() {
        Fixture fixture = agent(AgentStatus.PUBLISHED);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(AGENT_ID, "account-123"))
                .thenReturn(Optional.of(fixture.lineage()));

        assertThatThrownBy(() -> service.updateDraft("account-123", AGENT_ID,
                JsonNodeFactory.instance.objectNode().put("role", "Updated analyst"),
                "user-1", null))
                .isInstanceOf(InvalidAgentStatusTransitionException.class);
    }

    @Test
    void copiesAPublishedAgentAsAnIndependentVersionOneDraft() {
        Fixture source = agent(AgentStatus.PUBLISHED);
        when(copyRepository.findByLicenseCodeAndIdempotencyKey("account-123", "copy-key"))
                .thenReturn(Optional.empty());
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(AGENT_ID, "account-123"))
                .thenReturn(Optional.of(source.lineage()));
        when(repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(
                "account-123", "campaign qa agent v2")).thenReturn(false);
        when(repository.saveAndFlush(any(CustomAgentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.copy("account-123", AGENT_ID,
                new CopyCustomAgentRequest("Campaign QA Agent v2"), "copy-key", "user-1", null);

        assertThat(response.id()).isNotEqualTo(AGENT_ID);
        assertThat(response.status()).isEqualTo(AgentStatus.DRAFT);
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.definition().name()).isEqualTo("Campaign QA Agent v2");
    }

    @Test
    void retiresOnlyAfterRuntimeReportsNoActiveRuns() {
        Fixture fixture = agent(AgentStatus.PUBLISHED);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(AGENT_ID, "account-123"))
                .thenReturn(Optional.of(fixture.lineage()));
        when(repository.saveAndFlush(fixture.lineage())).thenReturn(fixture.lineage());
        when(runtimeRetirementClient.check("account-123", AGENT_ID))
                .thenReturn(new RetirementEligibilityResponse(true, 0, java.util.Map.of()));

        var response = service.updateStatus(
                "account-123", AGENT_ID, AgentStatus.RETIRED, "user-1", null);

        assertThat(response.status()).isEqualTo(AgentStatus.RETIRED);
        Assertions.assertThat(fixture.lineage().getStatus()).isEqualTo(AgentLineageStatus.RETIRED);
        Assertions.assertThat(fixture.version().getStatus()).isEqualTo(AgentVersionStatus.RETIRED);
        verify(runtimeRetirementClient).check("account-123", AGENT_ID);
    }

    @Test
    void publishesDraftAgentWithinTenant() {
        Fixture fixture = agent(AgentStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(AGENT_ID, "account-123"))
                .thenReturn(Optional.of(fixture.lineage()));
        when(repository.saveAndFlush(fixture.lineage())).thenReturn(fixture.lineage());

        var response = service.updateStatus(
                " account-123 ", AGENT_ID, AgentStatus.PUBLISHED, "user-1", null);

        Assertions.assertThat(fixture.version().getStatus()).isEqualTo(AgentVersionStatus.PUBLISHED);
        assertThat(fixture.lineage().getActiveVersion()).isEqualTo(1);
        assertThat(fixture.lineage().getDraftVersion()).isNull();
        assertThat(fixture.lineage().getUpdatedAt()).isEqualTo(NOW);
        Assertions.assertThat(response.status()).isEqualTo(AgentStatus.PUBLISHED);
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        verify(repository).saveAndFlush(fixture.lineage());
    }

    @Test
    void treatsPublishingAnAlreadyPublishedAgentAsIdempotent() {
        Fixture fixture = agent(AgentStatus.PUBLISHED);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(AGENT_ID, "account-123"))
                .thenReturn(Optional.of(fixture.lineage()));

        var response = service.updateStatus(
                "account-123", AGENT_ID, AgentStatus.PUBLISHED, "user-1", null);

        Assertions.assertThat(response.status()).isEqualTo(AgentStatus.PUBLISHED);
        verify(repository, never()).saveAndFlush(any(CustomAgentEntity.class));
        verify(versionRepository, never()).saveAndFlush(any(AgentVersionEntity.class));
    }

    @Test
    void rejectsPublishingARetiredAgent() {
        Fixture fixture = agent(AgentStatus.RETIRED);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(AGENT_ID, "account-123"))
                .thenReturn(Optional.of(fixture.lineage()));

        assertThatThrownBy(() -> service.updateStatus(
                "account-123", AGENT_ID, AgentStatus.PUBLISHED, "user-1", null))
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

    @Test
    void retiresOnlyAfterRetiringStateIsCommitted() {
        // The runtime reads RETIRING from the management database over HTTP, so the state has to
        // be persisted before the eligibility call. Merging the transaction boundaries would make
        // that query run against uncommitted state and silently stop excluding concurrent runs.
        Fixture fixture = agent(AgentStatus.PUBLISHED);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse(AGENT_ID, "account-123"))
                .thenReturn(Optional.of(fixture.lineage()));
        when(repository.saveAndFlush(fixture.lineage())).thenReturn(fixture.lineage());
        when(runtimeRetirementClient.check("account-123", AGENT_ID))
                .thenReturn(new RetirementEligibilityResponse(true, 0, java.util.Map.of()));

        service.updateStatus("account-123", AGENT_ID, AgentStatus.RETIRED, "user-1", null);

        InOrder order = inOrder(repository, runtimeRetirementClient);
        order.verify(repository).saveAndFlush(fixture.lineage());
        order.verify(runtimeRetirementClient).check("account-123", AGENT_ID);
    }

    private record Fixture(CustomAgentEntity lineage, AgentVersionEntity version) {
    }

    /** Builds an identity plus its single version in the shape the given API status implies. */
    private Fixture agent(AgentStatus status) {
        Instant createdAt = NOW.minusSeconds(3600);
        Integer activeVersion = null;
        Integer draftVersion = null;
        AgentLineageStatus lineageStatus = AgentLineageStatus.ACTIVE;
        AgentVersionStatus versionStatus;
        switch (status) {
            case DRAFT -> {
                draftVersion = 1;
                versionStatus = AgentVersionStatus.DRAFT;
            }
            case PUBLISHED -> {
                activeVersion = 1;
                versionStatus = AgentVersionStatus.PUBLISHED;
            }
            case RETIRED -> {
                activeVersion = 1;
                lineageStatus = AgentLineageStatus.RETIRED;
                versionStatus = AgentVersionStatus.RETIRED;
            }
            default -> throw new IllegalArgumentException("Unsupported fixture status " + status);
        }
        CustomAgentEntity lineage = new CustomAgentEntity(
                AGENT_ID, "account-123", "Campaign QA Agent", "campaign qa agent",
                "Checks campaign readiness", lineageStatus, activeVersion, draftVersion, 2,
                false, createdAt, createdAt, "user-0", "user-0", null);
        AgentVersionEntity version = new AgentVersionEntity(
                "5f9c1f0e-1f2a-4c1b-9d4e-7a1b2c3d4e5f", AGENT_ID, "account-123", 1,
                definitionJson(), versionStatus, createdAt, createdAt, "user-0", "user-0", null);
        lenient().when(versionRepository.findByAgentIdAndVersion(AGENT_ID, 1))
                .thenReturn(Optional.of(version));
        lenient().when(versionRepository.findByAgentIdOrderByVersionAsc(AGENT_ID))
                .thenReturn(List.of(version));
        lenient().when(versionRepository.saveAndFlush(any(AgentVersionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new Fixture(lineage, version);
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
