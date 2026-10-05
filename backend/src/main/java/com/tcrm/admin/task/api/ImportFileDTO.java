package com.tcrm.admin.task.api;

/**
 * Uploaded import file as received from the client.
 *
 * @param fileName original client file name; may be null
 * @param contentType client-declared MIME type; may be null
 * @param content raw bytes; must not be null
 */
public record ImportFileDTO(String fileName, String contentType, byte[] content) {}
