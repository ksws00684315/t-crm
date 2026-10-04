package com.tcrm.admin.common;

import java.util.List;

public record PageResult<T>(List<T> rows, long total) {}
