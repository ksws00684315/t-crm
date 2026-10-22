package com.tcrm.admin.system.controller;

import jakarta.validation.constraints.Size;
import java.time.Instant;

/** A BD resigning about itself: when it plans to leave and why. No heir may be named here. */
public record ResignationRequest(Instant plannedLeaveAt, @Size(max = 255) String reason) {}
