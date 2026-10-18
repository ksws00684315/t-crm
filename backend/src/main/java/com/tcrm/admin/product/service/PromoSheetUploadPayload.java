package com.tcrm.admin.product.service;

import com.tcrm.admin.product.api.PromoSheetRowDTO;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis side of a promo-sheet preview (task 26 §3.3): the parsed rows wait for confirmation under a
 * random {@code uploadId} for 30 minutes, bound to one company, KOL and uploader; the uploaded file
 * itself is never kept.
 *
 * @param tenantId company the upload ran in
 * @param kolId KOL the sheet was uploaded for
 * @param uploaderId uploading administrator
 * @param fileName original file name, for the preview and the operation log
 * @param rows parsed link-bearing rows, in sheet order
 */
public record PromoSheetUploadPayload(
    Long tenantId, Long kolId, Long uploaderId, String fileName, List<PromoSheetRowDTO> rows) {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public String serialize() {
    return JSON.writeValueAsString(this);
  }

  public static PromoSheetUploadPayload deserialize(String json) {
    return JSON.readValue(json, PromoSheetUploadPayload.class);
  }
}
