package com.tcrm.admin.product.service;

/** Creator-item list filter (task 39). Blank text is already null. */
public record KolItemFilterDTO(
    String linkCode,
    String kolKeyword,
    Long shopId,
    String status,
    int page,
    int size,
    String sourceItemId,
    String targetItemId,
    String productKeyword) {
  public KolItemFilterDTO(
      String linkCode, String kolKeyword, Long shopId, String status, int page, int size) {
    this(linkCode, kolKeyword, shopId, status, page, size, null, null, null);
  }
}
