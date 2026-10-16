package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;

public record EditUserCommand(
    String nickname, String email, Role role, PostTemplate postTemplate, DataScope dataScope) {}
