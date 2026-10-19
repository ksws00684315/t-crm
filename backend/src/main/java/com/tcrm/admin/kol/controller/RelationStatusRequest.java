package com.tcrm.admin.kol.controller;

import com.tcrm.admin.kol.model.RelationStatus;
import jakarta.validation.constraints.NotNull;

public record RelationStatusRequest(@NotNull RelationStatus status) {}
