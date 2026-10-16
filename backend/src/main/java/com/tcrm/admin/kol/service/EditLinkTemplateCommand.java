package com.tcrm.admin.kol.service;

/** Editable template fields; the code cannot change (task 11 §4.2). */
public record EditLinkTemplateCommand(String templateName, String templateUrl, Integer sortOrder) {}
