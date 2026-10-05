package com.tcrm.admin.task.service;

/** Downloadable task file. */
public record TaskFileDTO(String fileName, String contentType, byte[] content) {}
