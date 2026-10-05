package com.tcrm.admin.kol.controller;

import jakarta.validation.constraints.Min;

/** New BD; null clears the assignment. */
public record ChangeBdUserRequest(@Min(1) Long bdUserId) {}
