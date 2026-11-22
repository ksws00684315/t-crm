package com.tcrm.admin.product.service;

/** Filters for the product-task list. Blank fields are ignored. */
public record CopyJobQueryDTO(
    String jobCode,
    String targetChannel,
    String status,
    int page,
    int size,
    Long kolId,
    String targetItemId,
    Long tokenId) {
  public CopyJobQueryDTO(String jobCode, String targetChannel, String status, int page, int size) {
    this(jobCode, targetChannel, status, page, size, null, null, null);
  }
}
