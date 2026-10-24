package com.tcrm.admin.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tcrm.admin.integration.api.WeidianBusinessApi;
import com.tcrm.admin.integration.api.WeidianBusinessApi.AddItemResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemDetailResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemDetailResponseBlock;
import com.tcrm.admin.integration.api.WeidianBusinessApi.ItemResponse;
import com.tcrm.admin.integration.api.WeidianBusinessApi.SaveItemDetailResponse;
import com.tcrm.admin.integration.mapper.WeidianShopAuthMapper;
import com.tcrm.admin.product.api.WeidianItemCopyApi.CopyResult;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WeidianItemCopyServiceTest {
  private final WeidianBusinessApi weidian = mock(WeidianBusinessApi.class);
  private final WeidianShopAuthMapper auths = mock(WeidianShopAuthMapper.class);
  private final JsonMapper json = JsonMapper.builder().build();
  private final WeidianBusinessApiParser parser = new WeidianBusinessApiParser();
  private final Logger copyLogger = (Logger) LoggerFactory.getLogger(WeidianItemCopyService.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private WeidianItemCopyService service;

  @BeforeEach
  void setUp() {
    service = new WeidianItemCopyService(weidian, auths);
    logs.start();
    copyLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    copyLogger.detachAppender(logs);
  }

  @Test
  void shouldCopy_withoutCallingAdd_whenNameOrPriceMissing() {
    when(weidian.item(anyLong(), any()))
        .thenReturn(new ItemResponse("1", null, null, null, 1, "1", null));

    assertThatThrownBy(() -> service.copy(1L, "1", 2L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.getMessage()).isEqualTo(WeidianItemCopyService.SOURCE_INCOMPLETE);
            });
    verify(weidian, never()).addItem(anyLong(), any());
  }

  @Test
  void shouldReturnSuccessWithWarning_whenDetailSaveFailsAfterAdd() {
    when(weidian.item(anyLong(), any()))
        .thenReturn(new ItemResponse("10", "Name", null, new BigDecimal("3.5"), 2, "1", null));
    when(weidian.itemDetail(anyLong(), any()))
        .thenReturn(
            new ItemDetailResponse(
                List.of(new ItemDetailResponseBlock(1, "https://img", null, 1, null, null, null)),
                null));
    when(weidian.addItem(eq(2L), any())).thenReturn(new AddItemResponse(99L, List.of(), null));
    when(weidian.saveItemDetail(eq(2L), any()))
        .thenThrow(new WeidianApiException(500, "Weidian API error 500", false));

    CopyResult result = service.copy(1L, "10", 2L);

    assertThat(result.newItemId()).isEqualTo("99");
    assertThat(result.detailWarning()).isEqualTo(WeidianItemCopyService.DETAIL_SAVE_WARNING);
    verify(weidian).addItem(eq(2L), any());
    verify(weidian).saveItemDetail(eq(2L), any());
  }

  @Test
  void shouldReturnEmpty_whenProbeSeesExplicitNotFound() {
    when(weidian.item(anyLong(), any()))
        .thenThrow(new WeidianApiException(10003, "item not exist", false));

    assertThat(service.probe(1L, "404")).isEmpty();
  }

  @Test
  void shouldFailProbe_whenWeidianReturnsOtherError() {
    when(weidian.item(anyLong(), any()))
        .thenThrow(new WeidianApiException(50001, "Weidian API error 50001", false));

    assertThatThrownBy(() -> service.probe(1L, "1"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.getErrorCode()).isEqualTo(ErrorCode.WEIDIAN_API_FAILED);
              assertThat(be.getMessage()).contains("微店接口调用失败");
              assertThat(be.getMessage()).contains("50001");
            });
  }

  @Test
  void shouldProbeHit_whenItemExists() {
    when(weidian.item(anyLong(), any()))
        .thenReturn(new ItemResponse("7", "Hit", null, new BigDecimal("1.00"), 1, "1", null));

    Optional<?> hit = service.probe(3L, "7");
    assertThat(hit).isPresent();
  }

  @Test
  void shouldReportAuthorized_fromShopAuthCount() {
    when(auths.selectCount(any())).thenReturn(1L);
    assertThat(service.isShopAuthorized(9L)).isTrue();
    when(auths.selectCount(any())).thenReturn(0L);
    assertThat(service.isShopAuthorized(9L)).isFalse();
  }

  @Test
  void shouldTreatChineseNotExistMessage_asNotFound() {
    assertThat(WeidianItemCopyService.isItemNotFound(new WeidianApiException(1, "商品不存在", false)))
        .isTrue();
    assertThat(WeidianItemCopyService.isItemNotFound(new WeidianApiException(1, "不属于该店铺", false)))
        .isTrue();
    assertThat(
            WeidianItemCopyService.isItemNotFound(
                new WeidianApiException(999, "rate limited", true)))
        .isFalse();
  }

  @Test
  void shouldCopyFully_whenDetailSaveSucceeds() {
    when(weidian.item(anyLong(), any()))
        .thenReturn(new ItemResponse("10", "Name", null, new BigDecimal("3.5"), 2, "1", null));
    when(weidian.itemDetail(anyLong(), any())).thenReturn(new ItemDetailResponse(List.of(), null));
    when(weidian.addItem(eq(2L), any())).thenReturn(new AddItemResponse(88L, List.of(), null));

    CopyResult result = service.copy(1L, "10", 2L);

    assertThat(result.newItemId()).isEqualTo("88");
    assertThat(result.detailWarning()).isNull();
    verify(weidian, never()).saveItemDetail(anyLong(), any());
  }

  @Test
  void shouldSaveDetail_whenBlocksPresent() {
    when(weidian.item(anyLong(), any()))
        .thenReturn(new ItemResponse("10", "Name", null, new BigDecimal("3.5"), 2, "1", null));
    when(weidian.itemDetail(anyLong(), any()))
        .thenReturn(
            new ItemDetailResponse(
                List.of(new ItemDetailResponseBlock(1, "https://img", null, 1, null, null, null)),
                null));
    when(weidian.addItem(eq(2L), any())).thenReturn(new AddItemResponse(77L, List.of(), null));
    when(weidian.saveItemDetail(eq(2L), any())).thenReturn(new SaveItemDetailResponse("tmp", null));

    CopyResult result = service.copy(1L, "10", 2L);
    assertThat(result.detailWarning()).isNull();
    verify(weidian).saveItemDetail(eq(2L), any());
  }

  @Test
  void shouldCopyAndSaveDetail_whenAddReturnsItemidField() throws Exception {
    when(weidian.item(anyLong(), any()))
        .thenReturn(new ItemResponse("10", "Name", null, new BigDecimal("3.5"), 2, "1", null));
    when(weidian.itemDetail(anyLong(), any()))
        .thenReturn(
            new ItemDetailResponse(
                List.of(new ItemDetailResponseBlock(1, "https://img", null, 1, null, null, null)),
                null));
    when(weidian.addItem(eq(2L), any()))
        .thenReturn(parser.addItem(json.readTree("{\"add_sku_ids\":[],\"itemid\":2234677858}")));
    when(weidian.saveItemDetail(eq(2L), any())).thenReturn(new SaveItemDetailResponse("tmp", null));

    CopyResult result = service.copy(1L, "10", 2L);

    assertThat(result.newItemId()).isEqualTo("2234677858");
    assertThat(result.detailWarning()).isNull();
    verify(weidian).saveItemDetail(eq(2L), any());
  }

  @Test
  void shouldFailWithExplicitMessage_whenAddSucceedsButNoItemIdRecognised() throws Exception {
    when(weidian.item(anyLong(), any()))
        .thenReturn(new ItemResponse("10", "Name", null, new BigDecimal("3.5"), 2, "1", null));
    when(weidian.itemDetail(anyLong(), any())).thenReturn(new ItemDetailResponse(List.of(), null));
    JsonNode raw = json.readTree("{\"add_sku_ids\":[],\"new_goods\":\"SECRET-VALUE-42\"}");
    when(weidian.addItem(eq(2L), any())).thenReturn(new AddItemResponse(null, List.of(), raw));

    assertThatThrownBy(() -> service.copy(1L, "10", 2L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> {
              BusinessException be = (BusinessException) e;
              assertThat(be.getErrorCode()).isEqualTo(ErrorCode.WEIDIAN_API_FAILED);
              assertThat(be.getMessage()).isEqualTo(WeidianItemCopyService.ITEM_ID_MISSING);
            });
    verify(weidian, never()).saveItemDetail(anyLong(), any());
    assertThat(logs.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .isEqualTo(
                      "Weidian vdian.item.add returned success without a recognised item id"
                          + " in shop 2; result fields=[add_sku_ids, new_goods]");
            });
    assertThat(logs.list)
        .allSatisfy(
            event -> assertThat(event.getFormattedMessage()).doesNotContain("SECRET-VALUE-42"));
  }

  @Test
  void shouldLogInnerResultTypeOnly_whenAddResultNestsScalar() throws Exception {
    when(weidian.item(anyLong(), any()))
        .thenReturn(new ItemResponse("10", "Name", null, new BigDecimal("3.5"), 2, "1", null));
    when(weidian.itemDetail(anyLong(), any())).thenReturn(new ItemDetailResponse(List.of(), null));
    JsonNode raw = json.readTree("{\"result\":12345}");
    when(weidian.addItem(eq(2L), any())).thenReturn(new AddItemResponse(null, List.of(), raw));

    assertThatThrownBy(() -> service.copy(1L, "10", 2L)).isInstanceOf(BusinessException.class);

    assertThat(logs.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .isEqualTo(
                      "Weidian vdian.item.add returned success without a recognised item id"
                          + " in shop 2; result fields=[result], inner result type=NUMBER");
            });
    assertThat(logs.list)
        .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("12345"));
  }

  @Test
  void shouldLogJsonTypeOnly_whenAddResultIsNotAnObject() throws Exception {
    when(weidian.item(anyLong(), any()))
        .thenReturn(new ItemResponse("10", "Name", null, new BigDecimal("3.5"), 2, "1", null));
    when(weidian.itemDetail(anyLong(), any())).thenReturn(new ItemDetailResponse(List.of(), null));
    JsonNode raw = json.readTree("\"SECRET-VALUE-42\"");
    when(weidian.addItem(eq(2L), any())).thenReturn(new AddItemResponse(null, List.of(), raw));

    assertThatThrownBy(() -> service.copy(1L, "10", 2L)).isInstanceOf(BusinessException.class);

    assertThat(logs.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .isEqualTo(
                      "Weidian vdian.item.add returned success without a recognised item id"
                          + " in shop 2; result type=STRING");
            });
    assertThat(logs.list)
        .allSatisfy(
            event -> assertThat(event.getFormattedMessage()).doesNotContain("SECRET-VALUE-42"));
  }
}
