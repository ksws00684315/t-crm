package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tcrm.admin.integration.api.FxRateDTO;
import com.tcrm.admin.integration.mapper.FxRateMapper;
import com.tcrm.admin.integration.model.FxRateDO;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class FxRateServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-16T15:30:00Z");

  private final FxRateMapper mapper = mock(FxRateMapper.class);
  private final EcbRateClient ecb = mock(EcbRateClient.class);
  private final FxRateService service = new FxRateService(mapper, ecb);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(FxRateService.class);

  @BeforeEach
  void attachAppender() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(logs);
  }

  @Test
  void shouldInsertTheFetchedDate_whenUpdating() {
    when(ecb.fetch())
        .thenReturn(new EcbRate(LocalDate.of(2026, 10, 16), new BigDecimal("0.13974359")));
    when(mapper.insertIgnore(any())).thenReturn(1);
    when(mapper.selectLatest()).thenReturn(row(LocalDate.of(2026, 10, 16), "0.13974359"));

    service.update(NOW);

    ArgumentCaptor<FxRateDO> inserted = ArgumentCaptor.forClass(FxRateDO.class);
    verify(mapper).insertIgnore(inserted.capture());
    FxRateDO row = inserted.getValue();
    assertThat(row.getId()).isPositive();
    assertThat(row.getRateDate()).isEqualTo(LocalDate.of(2026, 10, 16));
    assertThat(row.getRate()).isEqualTo(new BigDecimal("0.13974359"));
    assertThat(row.getBase()).isEqualTo("CNY");
    assertThat(row.getQuote()).isEqualTo("USD");
    assertThat(row.getSource()).isEqualTo("ECB");
    assertThat(row.getFetchedTime()).isEqualTo(NOW);
    assertThat(row.getCreateTime()).isEqualTo(NOW);
    assertThat(warnings()).isEmpty();
  }

  @Test
  void shouldWarnWithoutInserting_whenTheFeedFails() {
    when(ecb.fetch()).thenThrow(new EcbFeedException("unexpected HTTP status 503"));
    when(mapper.selectLatest()).thenReturn(row(LocalDate.of(2026, 10, 15), "0.14019164"));

    service.update(NOW);

    verify(mapper, never()).insertIgnore(any());
    assertThat(warnings())
        .containsExactly("ECB reference rate fetch failed: unexpected HTTP status 503");
  }

  @Test
  void shouldNotThrow_whenTheDatabaseFails() {
    when(ecb.fetch())
        .thenReturn(new EcbRate(LocalDate.of(2026, 10, 16), new BigDecimal("0.13974359")));
    when(mapper.insertIgnore(any())).thenThrow(new IllegalStateException("db down"));
    when(mapper.selectLatest()).thenThrow(new IllegalStateException("db down"));

    service.update(NOW);

    assertThat(warnings())
        .containsExactly(
            "ECB reference rate store failed: IllegalStateException",
            "ECB reference rate staleness check failed: IllegalStateException");
  }

  @Test
  void shouldWarn_whenTheNewestRateIsMoreThanSevenDaysOld() {
    when(ecb.fetch()).thenThrow(new EcbFeedException("request failed: ConnectException"));
    // 2026-10-16 − 7 days = 2026-10-09: that date is still fresh, 2026-10-08 is stale.
    when(mapper.selectLatest()).thenReturn(row(LocalDate.of(2026, 10, 8), "0.14"));

    service.update(NOW);

    assertThat(warnings())
        .containsExactly(
            "ECB reference rate fetch failed: request failed: ConnectException",
            "Newest ECB reference rate is from 2026-10-08, older than 7 days");
  }

  @Test
  void shouldNotWarn_whenTheNewestRateIsExactlySevenDaysOld() {
    when(ecb.fetch()).thenReturn(new EcbRate(LocalDate.of(2026, 10, 9), new BigDecimal("0.14")));
    when(mapper.selectLatest()).thenReturn(row(LocalDate.of(2026, 10, 9), "0.14"));

    service.update(NOW);

    assertThat(warnings()).isEmpty();
  }

  @Test
  void shouldWarn_whenNoRateIsStored() {
    when(ecb.fetch()).thenThrow(new EcbFeedException("reference date missing"));

    service.update(NOW);

    assertThat(warnings())
        .containsExactly(
            "ECB reference rate fetch failed: reference date missing",
            "No ECB reference rate stored yet");
  }

  @Test
  void shouldReturnTheNewestRow_whenReadingLatest() {
    when(mapper.selectLatest()).thenReturn(row(LocalDate.of(2026, 10, 16), "0.13974359"));

    assertThat(service.latest())
        .contains(new FxRateDTO(LocalDate.of(2026, 10, 16), new BigDecimal("0.13974359")));
  }

  @Test
  void shouldReturnEmpty_whenNoRateIsStored() {
    assertThat(service.latest()).isEmpty();
  }

  private List<String> warnings() {
    return logs.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private static FxRateDO row(LocalDate date, String rate) {
    FxRateDO row = new FxRateDO();
    row.setId(1L);
    row.setRateDate(date);
    row.setRate(new BigDecimal(rate));
    return row;
  }
}
