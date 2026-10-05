package com.tcrm.admin.link.service;

import java.util.Map;

/** A raw {@code stream:click} entry: stream message ID and its fields. */
public record ClickMessage(String id, Map<String, String> fields) {}
