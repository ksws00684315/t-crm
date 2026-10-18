package com.tcrm.admin.product.service;

import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;

/**
 * Chinese operator-facing {@code msg} values for the external product-link APIs (design §6.5
 * exception to the English-msg rule).
 */
final class ProductLinkMessages {
  static final String TOKEN_INVALID = "复制令牌无效或已作废";
  static final String TOKEN_EXPIRED = "复制令牌已过期";
  static final String TOKEN_QUOTA = "复制令牌额度已用尽";
  static final String KOL_DISABLED = "达人账号已停用";
  static final String TARGET_MISSING = "未配置默认复制店铺，且令牌未指定目标店铺";
  static final String TARGET_DISABLED = "目标店铺已停用";
  static final String TARGET_UNAUTHORIZED = "目标店铺未完成微店授权";
  static final String JOB_NOT_FOUND = "任务编号不存在";
  static final String RATE_LIMITED = "请求过于频繁，请稍后再试";
  static final String VALIDATION = "请求参数无效";
  static final String UNSUPPORTED_CHANNEL = "不支持的来源渠道";
  static final String SOURCE_NOT_IN_SHOPS = "来源商品不在本公司已登记的店铺中";
  static final String SOURCE_CONFLICT = "多个店铺都有该商品";
  static final String SOURCE_INCOMPLETE = "来源商品信息不完整";
  static final String OTHER_KOL_EXCLUSIVE = "该商品是其他达人的专属商品";
  static final String QUOTA_INSUFFICIENT = "额度不足";
  static final String WEIDIAN_FAILED = "微店复制失败";
  static final String WEIDIAN_CALL_FAILED = "微店接口调用失败";
  static final String DETAIL_SAVE_WARNING = "商品已复制，详情保存失败，请在微店后台补充详情";
  static final String INTERRUPTED = "应用重启，任务中断";

  private ProductLinkMessages() {}

  static BusinessException chinese(ErrorCode code) {
    return new BusinessException(code, zh(code), null);
  }

  static String zh(ErrorCode code) {
    return switch (code) {
      case COPY_TOKEN_INVALID -> TOKEN_INVALID;
      case COPY_TOKEN_EXPIRED -> TOKEN_EXPIRED;
      case COPY_TOKEN_QUOTA_EXCEEDED -> TOKEN_QUOTA;
      case KOL_DISABLED -> KOL_DISABLED;
      case COPY_TARGET_SHOP_MISSING -> TARGET_MISSING;
      case SHOP_DISABLED -> TARGET_DISABLED;
      case WEIDIAN_SHOP_NOT_AUTHORIZED -> TARGET_UNAUTHORIZED;
      case COMMON_NOT_FOUND -> JOB_NOT_FOUND;
      case PRODUCT_LINK_RATE_LIMITED -> RATE_LIMITED;
      case COMMON_VALIDATION_FAILED -> VALIDATION;
      case WEIDIAN_API_FAILED -> WEIDIAN_CALL_FAILED;
      default -> code.message();
    };
  }

  static BusinessException wrap(BusinessException e) {
    if (e.hasCustomMessage()) {
      return e;
    }
    return new BusinessException(e.getErrorCode(), zh(e.getErrorCode()), e.getData());
  }

  static String mappingPersistFailed(String weidianItemId) {
    return "微店已创建商品 " + weidianItemId + "，系统登记失败，请人工核对";
  }
}
