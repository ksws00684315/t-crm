package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.mapper.KolMapper;
import org.springframework.stereotype.Component;

/**
 * Issues KOL codes from crm_kol_code_seq (task 04 §4.1, design §8.2): KOL + number padded to 4
 * digits, widening automatically past 9999; numbers whose code is already taken (for example by an
 * imported code, or a deleted KOL) are skipped.
 */
@Component
public class KolCodeGenerator {
  /** Safety bound for consecutive taken numbers; far above any realistic import. */
  static final int MAX_SKIPS = 10_000;

  private final KolMapper kols;

  public KolCodeGenerator(KolMapper kols) {
    this.kols = kols;
  }

  /** Returns a code that was free when checked; the unique index still guards concurrent use. */
  public String next() {
    for (int i = 0; i <= MAX_SKIPS; i++) {
      String code = KolRules.formatCode(kols.nextCodeNumber());
      if (!kols.existsCode(code)) {
        return code;
      }
    }
    throw new IllegalStateException("No free KOL code after " + MAX_SKIPS + " attempts");
  }
}
