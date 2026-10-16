package com.tcrm.admin.integration.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.tcrm.admin.integration.api.FxRateDTO;
import com.tcrm.admin.integration.api.FxRates;
import com.tcrm.admin.integration.mapper.FxRateMapper;
import com.tcrm.admin.integration.model.FxRateDO;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Stores and reads the ECB reference CNY to USD rate (design §7.5). */
@Service
public class FxRateService implements FxRates {
  /** The newest rate may be at most this many days old before a WARN (design §7.5). */
  static final int STALE_DAYS = 7;

  private static final Logger LOG = LoggerFactory.getLogger(FxRateService.class);

  private final FxRateMapper rates;
  private final EcbRateClient ecb;

  public FxRateService(FxRateMapper rates, EcbRateClient ecb) {
    this.rates = rates;
    this.ecb = ecb;
  }

  @Override
  public Optional<FxRateDTO> latest() {
    FxRateDO row = rates.selectLatest();
    return row == null
        ? Optional.empty()
        : Optional.of(new FxRateDTO(row.getRateDate(), row.getRate()));
  }

  /**
   * Fetches the ECB feed and stores its date unless already stored, then checks staleness. Never
   * throws: fetch, parse and database failures are logged at WARN and retried on the next run.
   */
  public void update(Instant now) {
    try {
      EcbRate fetched = ecb.fetch();
      FxRateDO row = new FxRateDO();
      row.setId(IdWorker.getId());
      row.setRateDate(fetched.date());
      row.setBase("CNY");
      row.setQuote("USD");
      row.setRate(fetched.cnyToUsd());
      row.setSource("ECB");
      row.setFetchedTime(now);
      row.setCreateTime(now);
      if (rates.insertIgnore(row) == 1) {
        LOG.info("Stored ECB reference rate {} CNY->USD for {}", row.getRate(), row.getRateDate());
      }
    } catch (EcbFeedException e) {
      LOG.warn("ECB reference rate fetch failed: {}", e.getMessage());
    } catch (RuntimeException e) {
      LOG.warn("ECB reference rate store failed: {}", e.getClass().getSimpleName());
    }
    warnIfStale(LocalDate.ofInstant(now, ZoneOffset.UTC));
  }

  private void warnIfStale(LocalDate today) {
    try {
      Optional<FxRateDTO> newest = latest();
      if (newest.isEmpty()) {
        LOG.warn("No ECB reference rate stored yet");
      } else if (newest.get().rateDate().isBefore(today.minusDays(STALE_DAYS))) {
        LOG.warn(
            "Newest ECB reference rate is from {}, older than {} days",
            newest.get().rateDate(),
            STALE_DAYS);
      }
    } catch (RuntimeException e) {
      LOG.warn("ECB reference rate staleness check failed: {}", e.getClass().getSimpleName());
    }
  }
}
