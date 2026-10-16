package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.service.EditLinkTemplateCommand;
import com.tcrm.admin.kol.service.LinkTemplateRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Editable template fields; the code is immutable and not accepted here. */
public record EditLinkTemplateRequest(
    @NotBlank @Size(max = LinkTemplateRules.MAX_NAME) String templateName,
    @NotBlank
        @Size(max = LinkTemplateRules.MAX_URL)
        @Pattern(regexp = LinkTemplateRules.URL_PATTERN)
        String templateUrl,
    @NotNull @Min(0) @Max(9999) Integer sortOrder) {
  EditLinkTemplateCommand toCommand() {
    return new EditLinkTemplateCommand(templateName.strip(), templateUrl.strip(), sortOrder);
  }
}
