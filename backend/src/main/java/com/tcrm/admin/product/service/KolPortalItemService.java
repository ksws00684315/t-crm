package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.model.KolPortalItemRow;
import com.tcrm.admin.security.api.CurrentKolContext;
import com.tcrm.admin.security.api.KolAccountDTO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * "My items" of the logged-in creator (task 26 §3.4): her current mappings with the name (original
 * product name first, then the sheet snapshot), the product image when there is one, and the
 * transit link {@code {crm.link.base-url}/i/{itemId}} the sheet script wrote. Identity and company
 * come from the KOL token only.
 */
@Service
public class KolPortalItemService {
  private static final int MAX_SIZE = 50;

  private final KolItemMapper items;
  private final CurrentKolContext current;
  private final SystemConfigApi configs;

  public KolPortalItemService(
      KolItemMapper items, CurrentKolContext current, SystemConfigApi configs) {
    this.items = items;
    this.current = current;
    this.configs = configs;
  }

  /** Page of the creator's current mappings, keyword-filtered by display name. */
  public PageResult<KolPortalItemDTO> page(int page, int size, String keyword) {
    if (page < 1 || page > PageResult.MAX_PAGE || size < 1 || size > MAX_SIZE) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    String filtered = keyword == null ? null : keyword.strip();
    if (filtered != null && filtered.isEmpty()) {
      filtered = null;
    }
    KolAccountDTO kol = current.kol();
    var result = items.pagePortalItems(new Page<>(page, size), kol.id(), filtered);
    List<KolPortalItemDTO> rows = result.getRecords().stream().map(row -> toDto(row, kol)).toList();
    return new PageResult<>(rows, result.getTotal(), page, size);
  }

  private KolPortalItemDTO toDto(KolPortalItemRow row, KolAccountDTO kol) {
    String productName = row.getProductName();
    String snapshot = row.getItemName();
    String name =
        productName != null && !productName.isBlank()
            ? productName
            : snapshot != null && !snapshot.isBlank() ? snapshot : null;
    return new KolPortalItemDTO(
        row.getWeidianItemId(),
        name,
        row.getImageUrl(),
        configs.getValue("url.link") + "/i/" + row.getWeidianItemId(),
        row.getCreateTime());
  }

  /** One row of the creator's item list. */
  public record KolPortalItemDTO(
      String weidianItemId,
      String itemName,
      String imageUrl,
      String transitUrl,
      Instant createTime) {}
}
