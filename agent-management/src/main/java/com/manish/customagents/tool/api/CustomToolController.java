package com.manish.customagents.tool.api;

import com.manish.customagents.tool.model.CreateToolRequest;
import com.manish.customagents.tool.model.ToolResponse;
import com.manish.customagents.tool.model.ToolStatusResponse;
import com.manish.customagents.tool.model.UpdateToolStatusRequest;
import com.manish.customagents.tool.service.CustomToolService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import com.manish.customagents.contracts.AgentApiHeaders;
import com.manish.customagents.contracts.LicenseCode;

@Validated
@RestController
@RequestMapping(path = "/api/v1/tools", produces = MediaType.APPLICATION_JSON_VALUE)
public class CustomToolController {


    private final CustomToolService toolService;

    public CustomToolController(CustomToolService toolService) {
        this.toolService = toolService;
    }

    @Operation(summary = "Create a dynamic tool definition")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ToolResponse> create(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE)
            @LicenseCode
            String licenseCode,
            @Valid @RequestBody CreateToolRequest request) {
        ToolResponse response = toolService.create(licenseCode, request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{toolId}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, location.toString())
                .body(response);
    }

    @Operation(summary = "List dynamic tool definitions")
    @GetMapping
    public ResponseEntity<List<ToolResponse>> list(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE)
            @LicenseCode
            String licenseCode) {
        return ResponseEntity.ok(toolService.list(licenseCode));
    }

    @Operation(summary = "Get a dynamic tool definition")
    @GetMapping("/{toolId}")
    public ResponseEntity<ToolResponse> get(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE)
            @LicenseCode
            String licenseCode,
            @PathVariable @NotBlank @Size(max = 36) String toolId) {
        return ResponseEntity.ok(toolService.get(licenseCode, toolId));
    }

    @Operation(summary = "Update a draft dynamic tool definition")
    @PatchMapping(path = "/{toolId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ToolResponse> update(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE)
            @LicenseCode
            String licenseCode,
            @PathVariable @NotBlank @Size(max = 36) String toolId,
            @Valid @RequestBody CreateToolRequest request) {
        return ResponseEntity.ok(toolService.updateDraft(licenseCode, toolId, request));
    }

    @Operation(summary = "Update dynamic tool status")
    @PatchMapping(path = "/{toolId}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ToolStatusResponse> updateStatus(
            @RequestHeader(AgentApiHeaders.LICENSE_CODE)
            @LicenseCode
            String licenseCode,
            @PathVariable @NotBlank @Size(max = 36) String toolId,
            @Valid @RequestBody UpdateToolStatusRequest request) {
        return ResponseEntity.ok(toolService.updateStatus(licenseCode, toolId, request.status()));
    }
}
