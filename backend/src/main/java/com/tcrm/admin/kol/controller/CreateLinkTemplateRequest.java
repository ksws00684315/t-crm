package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.CreateLinkTemplateCommand;
import com.tcrm.admin.kol.service.LinkTemplateRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** New template; {@code templateUrl} must be https and contain {@code {PRODUCT_ID}}. */
public record CreateLinkTemplateRequest(
    @NotBlank
        @Size(max = LinkTemplateRules.MAX_CODE)
        @Pattern(regexp = LinkTemplateRules.CODE_PATTERN)
        String templateCode,
    @NotBlank @Size(max = LinkTemplateRules.MAX_NAME) String templateName,
    @NotBlank
        @Size(max = LinkTemplateRules.MAX_URL)
        @Pattern(regexp = LinkTemplateRules.URL_PATTERN)
        String templateUrl,
    @NotNull @Min(0) @Max(9999) Integer sortOrder) {
  CreateLinkTemplateCommand toCommand() {
    return new CreateLinkTemplateCommand(
        templateCode.strip(), templateName.strip(), templateUrl.strip(), sortOrder);
  }
}
