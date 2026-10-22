package com.tcrm.admin.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcrm.admin.order.model.RefundStatus;
import com.tcrm.admin.order.model.RefundType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The single mapping place for raw Weidian refund fields (task 40 §3.1). Known values and unknown
 * values both matter; unknowns must never throw and must keep the original in raw_status.
 */
class RefundDictionaryTest {
  @Test
  void shouldMapTheOnlyKnownStatusToSuccess() {
    assertThat(RefundDictionary.statusOf("10")).isEqualTo(RefundStatus.SUCCESS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "5", "20", "30", "success", "REFUNDING", "10 ", " 10"})
  void shouldMapUnknownStatusesToUnknown(String raw) {
    assertThat(RefundDictionary.statusOf(raw)).isEqualTo(RefundStatus.UNKNOWN);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void shouldMapBlankStatusToUnknown(String raw) {
    assertThat(RefundDictionary.statusOf(raw)).isEqualTo(RefundStatus.UNKNOWN);
  }

  @Test
  void shouldMapKnownTypes() {
    assertThat(RefundDictionary.typeOf("1")).isEqualTo(RefundType.REFUND_ONLY);
    assertThat(RefundDictionary.typeOf("2")).isEqualTo(RefundType.RETURN_REFUND);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "3", "refund", "11"})
  void shouldMapUnknownTypesToUnknown(String raw) {
    assertThat(RefundDictionary.typeOf(raw)).isEqualTo(RefundType.UNKNOWN);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void shouldMapBlankTypeToUnknown(String raw) {
    assertThat(RefundDictionary.typeOf(raw)).isEqualTo(RefundType.UNKNOWN);
  }
}
