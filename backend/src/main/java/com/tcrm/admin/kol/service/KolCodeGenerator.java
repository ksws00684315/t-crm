package com.tcrm.admin.kol.service;

import com.tcrm.admin.kol.mapper.KolMapper;
import com.tcrm.admin.shared.TenantScope;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Issues KOL codes from crm_kol_code_seq (ADR-023, design §8.2): {@code KOL-} + 3 random uppercase
 * letters + sequence number padded to at least 3 digits; numbers whose code is already taken (for
 * example by an imported code, or a deleted KOL) are skipped.
 */
@Component
public class KolCodeGenerator {
  /** Safety bound for consecutive taken numbers; far above any realistic import. */
  static final int MAX_SKIPS = 10_000;

  private static final char[] LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

  private final KolMapper kols;
  private final SecureRandom random;

  @Autowired
  public KolCodeGenerator(KolMapper kols) {
    this(kols, new SecureRandom());
  }

  KolCodeGenerator(KolMapper kols, SecureRandom random) {
    this.kols = kols;
    this.random = random;
  }

  /** Returns a code that was free when checked; the unique index still guards concurrent use. */
  public String next() {
    for (int i = 0; i <= MAX_SKIPS; i++) {
      String code = KolRules.formatCode(randomLetters(), kols.nextCodeNumber());
      if (!TenantScope.callSystem("kol code uniqueness", () -> kols.existsCode(code))) {
        return code;
      }
    }
    throw new IllegalStateException("No free KOL code after " + MAX_SKIPS + " attempts");
  }

  private String randomLetters() {
    char[] out = new char[3];
    for (int i = 0; i < out.length; i++) {
      out[i] = LETTERS[random.nextInt(LETTERS.length)];
    }
    return new String(out);
  }
}
