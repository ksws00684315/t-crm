package com.tcrm.admin.order.service;

import static com.tcrm.admin.order.service.RefundImportColumn.AMOUNT;
import static com.tcrm.admin.order.service.RefundImportColumn.FINISH_TIME;
import static com.tcrm.admin.order.service.RefundImportColumn.ORDER_NO;
import static com.tcrm.admin.order.service.RefundImportColumn.REFUND_NO;
import static com.tcrm.admin.order.service.RefundImportColumn.REFUND_TIME;
import static com.tcrm.admin.order.service.RefundImportColumn.SHOP_ID;
import static com.tcrm.admin.order.service.RefundImportColumn.SUB_ORDER_NO;

import com.tcrm.admin.order.model.OrderSyncFailureKind;
import com.tcrm.admin.order.model.RefundDO;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.task.api.ImportReason;
import com.tcrm.admin.task.api.ImportRowProcessor;
import com.tcrm.admin.task.api.ImportTaskOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;

/** Imports one completed refund per Excel row (task 18 §4.3); {@code raw_status} is always 10. */
class RefundImportProcessor implements ImportRowProcessor {
  static final String SHOP_REQUIRED = "import.refund.error.shop-required";
  static final String SHOP_FORMAT = "import.refund.error.shop-format";
  static final String SHOP_MISSING = "import.refund.error.shop-missing";
  static final String REFUND_REQUIRED = "import.refund.error.refund-required";
  static final String REFUND_LENGTH = "import.refund.error.refund-length";
  static final String REFUND_EXISTS = "import.refund.error.refund-exists";
  static final String ORDER_REQUIRED = "import.refund.error.order-required";
  static final String ORDER_FORMAT = "import.refund.error.order-format";
  static final String ORDER_MISSING = "import.refund.error.order-missing";
  static final String SUB_ORDER_LENGTH = "import.refund.error.sub-order-length";
  static final String AMOUNT_REQUIRED = "import.refund.error.amount-required";
  static final String AMOUNT_FORMAT = "import.refund.error.amount-format";
  static final String REFUND_TIME_REQUIRED = "import.refund.error.refund-time-required";
  static final String REFUND_TIME_FORMAT = "import.refund.error.refund-time-format";
  static final String FINISH_TIME_REQUIRED = "import.refund.error.finish-time-required";
  static final String FINISH_TIME_FORMAT = "import.refund.error.finish-time-format";
  static final String FINISH_BEFORE_START = "import.refund.error.finish-before-start";

  private final ShopAccessApi shops;
  private final OrderWriter writer;
  private final OrderImportStateWriter importStates;
  private final OrderSyncFailureWriter syncFailures;
  private final long taskId;
  private final Instant dataCutoffAt;

  /** Shops whose import state was already written by this file; each shop is written once. */
  private final Set<Long> importShops = new HashSet<>();

  RefundImportProcessor(
      long taskId,
      ShopAccessApi shops,
      OrderWriter writer,
      OrderImportStateWriter importStates,
      OrderSyncFailureWriter syncFailures,
      Instant dataCutoffAt) {
    this.taskId = taskId;
    this.shops = shops;
    this.writer = writer;
    this.importStates = importStates;
    this.syncFailures = syncFailures;
    this.dataCutoffAt = dataCutoffAt;
  }

  @Override
  public List<ImportReason> process(List<String> row) {
    List<ImportReason> errors = new ArrayList<>();
    String shopWeidian = SHOP_ID.of(row);
    ShopAccessDTO shop = null;
    if (shopWeidian.isEmpty()) {
      errors.add(ImportReason.of(SHOP_REQUIRED));
    } else if (!OrderImportFields.isWeidianId(shopWeidian)) {
      errors.add(ImportReason.of(SHOP_FORMAT));
    } else {
      shop = shops.findEnabledByWeidianShopId(shopWeidian);
      if (shop == null) {
        errors.add(ImportReason.of(SHOP_MISSING));
      } else if (importShops.add(shop.id())) {
        importStates.record(shop.id(), "REFUND", taskId, dataCutoffAt);
      }
    }
    String refundNo = REFUND_NO.of(row);
    if (refundNo.isEmpty()) {
      errors.add(ImportReason.of(REFUND_REQUIRED));
    } else if (refundNo.length() > 32) {
      errors.add(ImportReason.of(REFUND_LENGTH));
    }
    String orderNo = ORDER_NO.of(row);
    if (orderNo.isEmpty()) {
      errors.add(ImportReason.of(ORDER_REQUIRED));
    } else if (!OrderImportFields.isWeidianId(orderNo)) {
      errors.add(ImportReason.of(ORDER_FORMAT));
    }
    String subOrder = SUB_ORDER_NO.of(row);
    if (!subOrder.isEmpty() && subOrder.length() > 128) {
      errors.add(ImportReason.of(SUB_ORDER_LENGTH));
    }
    String amountText = AMOUNT.of(row);
    BigDecimal amount = null;
    if (amountText.isEmpty()) {
      errors.add(ImportReason.of(AMOUNT_REQUIRED));
    } else {
      amount = OrderImportFields.money(amountText);
      if (amount == null || amount.signum() <= 0) {
        errors.add(ImportReason.of(AMOUNT_FORMAT));
      }
    }
    String refundTimeText = REFUND_TIME.of(row);
    Instant refundTime = null;
    if (refundTimeText.isEmpty()) {
      errors.add(ImportReason.of(REFUND_TIME_REQUIRED));
    } else {
      refundTime = OrderImportFields.beijingTime(refundTimeText);
      if (refundTime == null) {
        errors.add(ImportReason.of(REFUND_TIME_FORMAT));
      }
    }
    String finishTimeText = FINISH_TIME.of(row);
    Instant finishTime = null;
    if (finishTimeText.isEmpty()) {
      errors.add(ImportReason.of(FINISH_TIME_REQUIRED));
    } else {
      finishTime = OrderImportFields.beijingTime(finishTimeText);
      if (finishTime == null) {
        errors.add(ImportReason.of(FINISH_TIME_FORMAT));
      }
    }
    if (refundTime != null && finishTime != null && finishTime.isBefore(refundTime)) {
      errors.add(ImportReason.of(FINISH_BEFORE_START));
    }
    if (!errors.isEmpty() || shop == null) {
      return errors;
    }
    if (writer.findRefund(refundNo) != null) {
      return List.of(ImportReason.of(REFUND_EXISTS));
    }
    if (writer.find(shop.id(), orderNo) == null) {
      return List.of(ImportReason.of(ORDER_MISSING));
    }
    RefundDO rowDo = new RefundDO();
    rowDo.setWeidianRefundNo(refundNo);
    rowDo.setAmount(amount);
    rowDo.setRefundTime(refundTime);
    rowDo.setFinishTime(finishTime);
    try {
      if (!writer.saveImportedRefund(shop.id(), orderNo, subOrder, rowDo)) {
        return List.of(ImportReason.of(ORDER_MISSING));
      }
    } catch (DuplicateKeyException e) {
      return List.of(ImportReason.of(REFUND_EXISTS));
    }
    return List.of();
  }

  @Override
  public void onFinish(ImportTaskOutcome outcome) {
    if (outcome.status() != ImportTaskOutcome.Status.SUCCESS) {
      syncFailures.record(
          null,
          OrderSyncFailureKind.REFUND_IMPORT,
          "Refund import finished "
              + outcome.status()
              + ": "
              + outcome.failed()
              + " of "
              + outcome.total()
              + " rows failed",
          taskId);
    }
  }
}
