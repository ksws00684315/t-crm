package com.tcrm.admin.system.service;

import com.tcrm.admin.security.api.DataScope;
import com.tcrm.admin.security.api.PostTemplate;
import com.tcrm.admin.security.api.Role;
import java.util.List;

public record CreateUserCommand(
    String username,
    String password,
    String nickname,
    String email,
    Role role,
    PostTemplate postTemplate,
    DataScope dataScope,
    List<String> permissions) {}
