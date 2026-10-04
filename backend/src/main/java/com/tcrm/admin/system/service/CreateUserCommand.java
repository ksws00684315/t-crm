package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.Role;

public record CreateUserCommand(
    String username,
    String password,
    String nickname,
    String email,
    Role role,
    DataScope dataScope) {}
