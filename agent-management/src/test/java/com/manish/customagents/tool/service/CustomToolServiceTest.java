package com.manish.customagents.tool.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manish.customagents.contracts.CredentialType;
import com.manish.customagents.credential.entity.TenantCredentialEntity;
import com.manish.customagents.credential.service.CredentialReferenceScanner;
import com.manish.customagents.credential.service.TenantCredentialService;
import com.manish.customagents.credential.service.ToolCredentialValidator;
import com.manish.customagents.egress.service.TenantEgressHostService;
import com.manish.customagents.egress.service.ToolEgressValidator;
import com.manish.customagents.error.CredentialNotFoundException;
import com.manish.customagents.error.DuplicateToolNameException;
import com.manish.customagents.error.ToolHostNotAllowedException;
import com.manish.customagents.tool.enums.ToolStatus;
import com.manish.customagents.contracts.ToolType;
import com.manish.customagents.budget.DefinitionBudgetValidator;
import com.manish.customagents.error.DefinitionBudgetExceededException;
import com.manish.customagents.contracts.PromptBudget;
import com.manish.customagents.tool.entity.CustomToolEntity;
import com.manish.customagents.tool.model.CreateToolRequest;
import com.manish.customagents.tool.repository.CustomToolRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomToolServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    @Mock
    private CustomToolRepository repository;

    @Mock
    private TenantEgressHostService egressHosts;

    @Mock
    private TenantCredentialService credentialService;

    private CustomToolService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new CustomToolService(
                repository,
                new ToolDefinitionJsonMapper(objectMapper),
                new DefinitionBudgetValidator(objectMapper),
                new ToolEgressValidator(egressHosts),
                new ToolCredentialValidator(
                        credentialService, new CredentialReferenceScanner(repository, objectMapper)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsTenantScopedDraftDefinition() {
        when(repository.saveAndFlush(any(CustomToolEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create(" tenant-1 ", request(" campaign.get "));

        ArgumentCaptor<CustomToolEntity> captor = ArgumentCaptor.forClass(CustomToolEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getNormalizedName()).isEqualTo("campaign.get");
        assertThat(captor.getValue().getDefinitionJson()).contains("api.example.com");
        assertThat(response.status()).isEqualTo(ToolStatus.DRAFT);
        assertThat(response.version()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateNameWithinTenant() {
        when(repository.existsByLicenseCodeAndNormalizedNameAndDeletedFalse(
                "tenant-1", "campaign.get")).thenReturn(true);

        assertThatThrownBy(() -> service.create("tenant-1", request("campaign.get")))
                .isInstanceOf(DuplicateToolNameException.class);
    }

    @Test
    void publishesDraftTool() {
        CustomToolEntity entity = new CustomToolEntity(
                "tool-1", "tenant-1", "campaign.get", "campaign.get", "Gets campaign",
                ToolType.HTTP, "{}", ToolStatus.DRAFT, 1, false,
                NOW.minusSeconds(60), NOW.minusSeconds(60));
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));

        var response = service.updateStatus("tenant-1", "tool-1", ToolStatus.PUBLISHED);

        assertThat(response.status()).isEqualTo(ToolStatus.PUBLISHED);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        verify(repository).saveAndFlush(entity);
    }

    @Test
    void refusesToPublishAToolThatIsOverThePromptBudget() {
        CreateToolRequest oversized = new CreateToolRequest(
                "campaign.get",
                "x".repeat(PromptBudget.MAX_TOOL_CHARACTERS + 1),
                ToolType.HTTP,
                objectMapper.createObjectNode().put("type", "object"),
                null,
                objectMapper.createObjectNode()
                        .put("method", "GET").put("url", "https://api.example.com/campaigns"),
                null);
        CustomToolEntity entity = entity(oversized, ToolStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateStatus("tenant-1", "tool-1", ToolStatus.PUBLISHED))
                .isInstanceOf(DefinitionBudgetExceededException.class);

        // The draft is left exactly as it was; only publishing is refused.
        assertThat(entity.getStatus()).isEqualTo(ToolStatus.DRAFT);
        verify(repository, never()).saveAndFlush(entity);
    }

    @Test
    void listsAndGetsTenantScopedDefinitions() {
        CreateToolRequest definition = request("campaign.get");
        CustomToolEntity entity = entity(definition, ToolStatus.DRAFT);
        when(repository.findByLicenseCodeAndDeletedFalseOrderByUpdatedAtDesc("tenant-1"))
                .thenReturn(List.of(entity));
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));

        assertThat(service.list(" tenant-1 ")).extracting(response -> response.id())
                .containsExactly("tool-1");
        assertThat(service.get("tenant-1", "tool-1").definition().name())
                .isEqualTo("campaign.get");
    }

    @Test
    void updatesADraftWithoutChangingItsVersion() {
        CreateToolRequest original = request("campaign.get");
        CustomToolEntity entity = entity(original, ToolStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));

        CreateToolRequest updated = request("campaign.read");
        var response = service.updateDraft("tenant-1", "tool-1", updated);

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.definition().name()).isEqualTo("campaign.read");
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        verify(repository).saveAndFlush(entity);
    }

    @Test
    void refusesToPublishAToolWhoseHostIsNotOnTheTenantAllowlist() {
        CustomToolEntity entity = entity(request("campaign.get"), ToolStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));
        when(egressHosts.activePatterns("tenant-1")).thenReturn(List.of("api.other.com"));

        assertThatThrownBy(() -> service.updateStatus("tenant-1", "tool-1", ToolStatus.PUBLISHED))
                .isInstanceOf(ToolHostNotAllowedException.class)
                .hasMessageContaining("api.example.com");

        assertThat(entity.getStatus()).isEqualTo(ToolStatus.DRAFT);
        verify(repository, never()).saveAndFlush(entity);
    }

    @Test
    void publishesWhenAWildcardPatternCoversTheHost() {
        CustomToolEntity entity = entity(request("campaign.get"), ToolStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));
        when(egressHosts.activePatterns("tenant-1")).thenReturn(List.of("*.example.com"));

        assertThat(service.updateStatus("tenant-1", "tool-1", ToolStatus.PUBLISHED).status())
                .isEqualTo(ToolStatus.PUBLISHED);
    }

    @Test
    void refusesToPublishAToolWhoseHostIsItselfATemplateVariable() {
        // The destination would be chosen by tool arguments at run time, so the allowlist could not
        // meaningfully constrain it.
        CreateToolRequest templatedHost = new CreateToolRequest(
                "campaign.get",
                "Gets a campaign",
                ToolType.HTTP,
                objectMapper.createObjectNode().put("type", "object"),
                null,
                objectMapper.createObjectNode()
                        .put("method", "GET").put("url", "https://{host}/campaigns"),
                null);
        CustomToolEntity entity = entity(templatedHost, ToolStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.updateStatus("tenant-1", "tool-1", ToolStatus.PUBLISHED))
                .isInstanceOf(ToolHostNotAllowedException.class)
                .hasMessageContaining("literal host");

        verify(repository, never()).saveAndFlush(entity);
    }

    @Test
    void refusesToPublishAToolWhoseCredentialDoesNotExist() {
        CustomToolEntity entity = entity(authenticatedRequest("google-sheets"), ToolStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));
        when(egressHosts.activePatterns("tenant-1")).thenReturn(List.of("api.example.com"));
        when(credentialService.requireActiveByName("tenant-1", "google-sheets"))
                .thenThrow(new CredentialNotFoundException("google-sheets"));

        assertThatThrownBy(() -> service.updateStatus("tenant-1", "tool-1", ToolStatus.PUBLISHED))
                .isInstanceOf(CredentialNotFoundException.class);

        assertThat(entity.getStatus()).isEqualTo(ToolStatus.DRAFT);
        verify(repository, never()).saveAndFlush(entity);
    }

    @Test
    void publishesAToolWhoseCredentialIsActive() {
        CustomToolEntity entity = entity(authenticatedRequest("google-sheets"), ToolStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));
        when(egressHosts.activePatterns("tenant-1")).thenReturn(List.of("api.example.com"));
        when(credentialService.requireActiveByName("tenant-1", "google-sheets"))
                .thenReturn(new TenantCredentialEntity(
                        "tenant-1", "google-sheets", CredentialType.GOOGLE_SERVICE_ACCOUNT, null,
                        "v1.aa.bb", "primary", "{}", "admin", NOW));

        assertThat(service.updateStatus("tenant-1", "tool-1", ToolStatus.PUBLISHED).status())
                .isEqualTo(ToolStatus.PUBLISHED);
    }

    /** A tool with no auth block must not consult the credential store at all. */
    @Test
    void doesNotLookUpACredentialForAToolThatNeedsNoAuthentication() {
        CustomToolEntity entity = entity(request("campaign.get"), ToolStatus.DRAFT);
        when(repository.findByIdAndLicenseCodeAndDeletedFalse("tool-1", "tenant-1"))
                .thenReturn(Optional.of(entity));
        when(egressHosts.activePatterns("tenant-1")).thenReturn(List.of("api.example.com"));

        service.updateStatus("tenant-1", "tool-1", ToolStatus.PUBLISHED);

        verify(credentialService, never()).requireActiveByName(any(), any());
    }

    private CreateToolRequest authenticatedRequest(String credentialName) {
        var configuration = objectMapper.createObjectNode()
                .put("method", "GET")
                .put("url", "https://api.example.com/v1/values");
        configuration.putObject("auth").put("credential", credentialName);
        return new CreateToolRequest(
                "sheets.read", "Reads a range", ToolType.HTTP,
                objectMapper.createObjectNode().put("type", "object"),
                null, configuration, null);
    }

    private CustomToolEntity entity(CreateToolRequest definition, ToolStatus status) {
        return new CustomToolEntity(
                "tool-1", "tenant-1", definition.name(), definition.name().toLowerCase(),
                definition.description(), definition.type(),
                new ToolDefinitionJsonMapper(objectMapper).write(definition), status, 1, false,
                NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private CreateToolRequest request(String name) {
        return new CreateToolRequest(
                name,
                "Gets a campaign",
                ToolType.HTTP,
                objectMapper.createObjectNode().put("type", "object"),
                null,
                objectMapper.createObjectNode()
                        .put("method", "GET")
                        .put("url", "https://api.example.com/campaigns/{campaignId}"),
                null);
    }
}
