package com.tcrm.admin.integration.api;

import java.time.Instant;

public record WeidianAppView(String appKey, boolean secretConfigured, Instant updateTime) {}
