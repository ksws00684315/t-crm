package com.tcrm.admin.product.service;

import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.model.KolItemDO;
import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;

/**
 * Assigns the immutable link code ({@code KPL-} plus 12 uppercase hex characters) and inserts the
 * mapping. A collision on {@code uk_crm_kol_item_link} is retried. Any other unique-key conflict is
 * the caller's business conflict and is not retried.
 */
public final class KolItemCodes {
  static final int MAX_ATTEMPTS = 5;
  private static final String LINK_KEY = "uk_crm_kol_item_link";
  private static final SecureRandom RANDOM = new SecureRandom();

  private KolItemCodes() {}

  /** A new code. Uniqueness is enforced by the insert, not by this method. */
  public static String next() {
    byte[] bytes = new byte[6];
    RANDOM.nextBytes(bytes);
    StringBuilder code = new StringBuilder(16);
    code.append("KPL-");
    for (byte value : bytes) {
      code.append(Character.forDigit((value >> 4) & 0xf, 16));
      code.append(Character.forDigit(value & 0xf, 16));
    }
    return code.toString().toUpperCase(Locale.ROOT);
  }

  /** Sets a fresh link code and inserts. Clears a generated id before each retry. */
  public static void insertNew(KolItemMapper items, KolItemDO row) {
    for (int attempt = 1; ; attempt++) {
      row.setLinkCode(next());
      try {
        items.insert(row);
        return;
      } catch (DuplicateKeyException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (!message.contains(LINK_KEY) || attempt >= MAX_ATTEMPTS) {
          throw ex;
        }
        row.setId(null);
      }
    }
  }
}
