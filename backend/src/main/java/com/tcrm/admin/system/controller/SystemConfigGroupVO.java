package com.tcrm.admin.system.controller;

import java.util.List;

public record SystemConfigGroupVO(String group, List<SystemConfigVO> items) {}
