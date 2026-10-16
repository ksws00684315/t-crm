package com.tcrm.admin.integration.api;

import java.util.Optional;

/** Read access to the ECB reference CNY to USD rate (design §7.5), for display only. */
public interface FxRates {
  /**
   * Newest stored rate. Works in any company or system scope because the rate is platform-level.
   *
   * @return the rate with the latest date, or empty when none has been fetched yet (show CNY only)
   */
  Optional<FxRateDTO> latest();
}
