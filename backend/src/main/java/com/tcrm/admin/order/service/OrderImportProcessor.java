package com.tcrm.admin.order.service;

import static com.tcrm.admin.order.service.OrderImportColumn.FREIGHT;
import static com.tcrm.admin.order.service.OrderImportColumn.ITEM_ID;
import static com.tcrm.admin.order.service.OrderImportColumn.ITEM_NAME;
import static com.tcrm.admin.order.service.OrderImportColumn.ITEM_PAY_AMOUNT;
import static com.tcrm.admin.order.service.OrderImportColumn.ORDER_NO;
import static com.tcrm.admin.order.service.OrderImportColumn.ORDER_TIME;
import static com.tcrm.admin.order.service.OrderImportColumn.PAY_AMOUNT;
import static com.tcrm.admin.order.service.OrderImportColumn.PAY_TIME;
import static com.tcrm.admin.order.service.OrderImportColumn.QUANTITY;
import static com.tcrm.admin.order.service.OrderImportColumn.SHOP_ID;
import static com.tcrm.admin.order.service.OrderImportColumn.SKU_ID;
import static com.tcrm.admin.order.service.OrderImportColumn.SKU_NAME;
import static com.tcrm.admin.order.service.OrderImportColumn.STATUS;
import static com.tcrm.admin.order.service.OrderImportColumn.SUB_ORDER_NO;

import com.tcrm.admin.order.model.OrderDO;
import com.tcrm.admin.order.model.OrderItemDO;
import com.tcrm.admin.order.model.OrderStatus;
import com.tcrm.admin.order.model.OrderSyncFailureKind;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.task.api.ImportReason;
import com.tcrm.admin.task.api.ImportRowProcessor;
import com.tcrm.admin.task.api.ImportTaskOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports one order line per Excel row (task 18 §4.2). Same order across rows is written as one
 * head plus each line; empty sub-order ids get a stable key from item + SKU + occurrence index
 * within the same order (counted as soon as shop / order / item ids parse, even if the row later
 * fails other checks).
 */
class OrderImportProcessor implements ImportRowProcessor {
  static final String SHOP_REQUIRED = "import.order.error.shop-required";
  static final String SHOP_FORMAT = "import.order.error.shop-format";
  static final String SHOP_MISSING = "import.order.error.shop-missing";
  static final String ORDER_REQUIRED = "import.order.error.order-required";
  static final String ORDER_FORMAT = "import.order.error.order-format";
  static final String SUB_ORDER_LENGTH = "import.order.error.sub-order-length";
  static final String STATUS_REQUIRED = "import.order.error.status-required";
  static final String STATUS_FORMAT = "import.order.error.status-format";
  static final String ORDER_TIME_REQUIRED = "import.order.error.order-time-required";
  static final String ORDER_TIME_FORMAT = "import.order.error.order-time-format";
  static final String PAY_TIME_REQUIRED = "import.order.error.pay-time-required";
  static final String PAY_TIME_FORMAT = "import.order.error.pay-time-format";
  static final String PAY_AMOUNT_REQUIRED = "import.order.error.pay-amount-required";
  static final String PAY_AMOUNT_FORMAT = "import.order.error.pay-amount-format";
  static final String FREIGHT_FORMAT = "import.order.error.freight-format";
  static final String ITEM_REQUIRED = "import.order.error.item-required";
  static final String ITEM_FORMAT = "import.order.error.item-format";
  static final String SKU_LENGTH = "import.order.error.sku-length";
  static final String NAME_REQUIRED = "import.order.error.name-required";
  static final String NAME_LENGTH = "import.order.error.name-length";
  static final String SKU_NAME_LENGTH = "import.order.error.sku-name-length";
  static final String QUANTITY_REQUIRED = "import.order.error.quantity-required";
  static final String QUANTITY_FORMAT = "import.order.error.quantity-format";
  static final String ITEM_PAY_REQUIRED = "import.order.error.item-pay-required";
  static final String ITEM_PAY_FORMAT = "import.order.error.item-pay-format";
  static final String SYNC_OWNED = "import.order.error.sync-owned";

  private final long taskId;
  private final ShopAccessApi shops;
  private final OrderWriter writer;
  private final OrderImportStateWriter importStates;
  private final OrderSyncFailureWriter syncFailures;
  private final Instant dataCutoffAt;

  /**
   * Per-file occurrence count of the same shop+order+item+SKU. Incremented as soon as those ids
   * parse, so a failed row still reserves its index and a later fix+reimport keeps other keys.
   */
  private final Map<String, Integer> lineIndexes = new HashMap<>();

  /** Shops whose import state was already written by this file; each shop is written once. */
  private final Set<Long> importShops = new HashSet<>();

  OrderImportProcessor(
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
      } else {
        recordImport(shop);
      }
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
    String statusText = STATUS.of(row);
    OrderStatus status = null;
    if (statusText.isEmpty()) {
      errors.add(ImportReason.of(STATUS_REQUIRED));
    } else {
      status = OrderImportFields.status(statusText);
      if (status == null || status == OrderStatus.UNKNOWN) {
        errors.add(ImportReason.of(STATUS_FORMAT));
      }
    }
    String orderTimeText = ORDER_TIME.of(row);
    Instant orderTime = null;
    if (orderTimeText.isEmpty()) {
      errors.add(ImportReason.of(ORDER_TIME_REQUIRED));
    } else {
      orderTime = OrderImportFields.beijingTime(orderTimeText);
      if (orderTime == null) {
        errors.add(ImportReason.of(ORDER_TIME_FORMAT));
      }
    }
    String payTimeText = PAY_TIME.of(row);
    Instant payTime = null;
    if (!payTimeText.isEmpty()) {
      payTime = OrderImportFields.beijingTime(payTimeText);
      if (payTime == null) {
        errors.add(ImportReason.of(PAY_TIME_FORMAT));
      }
    } else if (status != null && OrderImportFields.payTimeRequired(status)) {
      errors.add(ImportReason.of(PAY_TIME_REQUIRED));
    }
    String payAmountText = PAY_AMOUNT.of(row);
    BigDecimal payAmount = null;
    if (payAmountText.isEmpty()) {
      errors.add(ImportReason.of(PAY_AMOUNT_REQUIRED));
    } else {
      payAmount = OrderImportFields.money(payAmountText);
      if (payAmount == null) {
        errors.add(ImportReason.of(PAY_AMOUNT_FORMAT));
      }
    }
    String freightText = FREIGHT.of(row);
    BigDecimal freight = BigDecimal.ZERO.setScale(2);
    if (!freightText.isEmpty()) {
      freight = OrderImportFields.money(freightText);
      if (freight == null) {
        errors.add(ImportReason.of(FREIGHT_FORMAT));
      }
    }
    String itemId = ITEM_ID.of(row);
    if (itemId.isEmpty()) {
      errors.add(ImportReason.of(ITEM_REQUIRED));
    } else if (!OrderImportFields.isWeidianId(itemId)) {
      errors.add(ImportReason.of(ITEM_FORMAT));
    }
    String skuId = SKU_ID.of(row);
    if (!skuId.isEmpty() && skuId.length() > 32) {
      errors.add(ImportReason.of(SKU_LENGTH));
    }
    // Reserve occurrence index as soon as shop / order / item ids parse (even if other checks
    // fail).
    Integer occurrence = null;
    if (OrderImportFields.isWeidianId(shopWeidian)
        && OrderImportFields.isWeidianId(orderNo)
        && OrderImportFields.isWeidianId(itemId)
        && (skuId.isEmpty() || skuId.length() <= 32)) {
      String skuKey = skuId.isEmpty() ? "" : skuId;
      String occurrenceKey = shopWeidian + ":" + orderNo + ":" + itemId + ":" + skuKey;
      int index = lineIndexes.getOrDefault(occurrenceKey, 0);
      lineIndexes.put(occurrenceKey, index + 1);
      occurrence = index;
    }
    String itemName = ITEM_NAME.of(row);
    if (itemName.isEmpty()) {
      errors.add(ImportReason.of(NAME_REQUIRED));
    } else if (OrderImportFields.codePointLength(itemName) > 255) {
      errors.add(ImportReason.of(NAME_LENGTH));
    }
    String skuName = SKU_NAME.of(row);
    if (!skuName.isEmpty() && OrderImportFields.codePointLength(skuName) > 255) {
      errors.add(ImportReason.of(SKU_NAME_LENGTH));
    }
    String quantityText = QUANTITY.of(row);
    Integer quantity = null;
    if (quantityText.isEmpty()) {
      errors.add(ImportReason.of(QUANTITY_REQUIRED));
    } else {
      quantity = OrderImportFields.quantity(quantityText);
      if (quantity == null) {
        errors.add(ImportReason.of(QUANTITY_FORMAT));
      }
    }
    String itemPayText = ITEM_PAY_AMOUNT.of(row);
    BigDecimal itemPay = null;
    if (itemPayText.isEmpty()) {
      errors.add(ImportReason.of(ITEM_PAY_REQUIRED));
    } else {
      itemPay = OrderImportFields.money(itemPayText);
      if (itemPay == null) {
        errors.add(ImportReason.of(ITEM_PAY_FORMAT));
      }
    }
    if (!errors.isEmpty() || shop == null || status == null || orderTime == null) {
      return errors;
    }
    OrderDO existing = writer.find(shop.id(), orderNo);
    if (existing != null && Integer.valueOf(1).equals(existing.getSource())) {
      return List.of(ImportReason.of(SYNC_OWNED));
    }
    String sku = skuId.isEmpty() ? "" : skuId;
    String resolvedSub =
        subOrder.isEmpty()
            ? "line-" + OrderNormalizer.hash(itemId + ":" + sku + ":" + occurrence)
            : subOrder;
    Instant now = Instant.now();
    OrderDO order = new OrderDO();
    order.setShopId(shop.id());
    order.setWeidianOrderId(orderNo);
    order.setOrderStatus(status.name());
    order.setRawStatus("IMPORT:" + status.name());
    order.setOrderTime(orderTime);
    order.setPayTime(payTime);
    order.setTotalAmount(payAmount);
    order.setPayAmount(payAmount);
    order.setFreight(freight);
    order.setDiscount(BigDecimal.ZERO.setScale(2));
    order.setSource(2);
    order.setImportBatchId(taskId);
    order.setSourceUpdateTime(now);
    order.setLastSyncTime(now);
    order.setAttributionStatus("PENDING");
    order.setBuyerHash(null);
    OrderItemDO item = new OrderItemDO();
    item.setSubOrderId(resolvedSub);
    item.setWeidianItemId(itemId);
    item.setSkuId(sku);
    item.setItemName(itemName);
    item.setSkuName(skuName.isEmpty() ? "" : skuName);
    item.setQuantity(quantity);
    item.setPayAmount(itemPay);
    item.setRefundAmount(BigDecimal.ZERO.setScale(2));
    item.setAttributionType(0);
    writer.save(new OrderPayloadDTO(order, List.of(item)));
    return List.of();
  }

  /** One row per shop is enough: every row of the file declares the same cutoff time. */
  private void recordImport(ShopAccessDTO shop) {
    if (importShops.add(shop.id())) {
      importStates.record(shop.id(), "ORDER", taskId, dataCutoffAt);
    }
  }

  @Override
  public void onFinish(ImportTaskOutcome outcome) {
    if (outcome.status() != ImportTaskOutcome.Status.SUCCESS) {
      syncFailures.record(
          null,
          OrderSyncFailureKind.ORDER_IMPORT,
          "Order import finished "
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
