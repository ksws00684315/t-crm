package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tcrm.admin.kol.api.CopyTargetShopApi;
import com.tcrm.admin.kol.api.CopyTokenApi;
import com.tcrm.admin.kol.api.CopyTokenGrantDTO;
import com.tcrm.admin.product.api.WeidianItemCopyApi;
import com.tcrm.admin.product.mapper.CopyJobItemMapper;
import com.tcrm.admin.product.mapper.CopyJobMapper;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.CopyJobDO;
import com.tcrm.admin.product.model.CopyJobItemDO;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.ProductDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * External product-link APIs for the customer spreadsheet script (task 17b / ADR-019). Controllers
 * stay thin; company scope is opened from the token or job after a system-scope lookup.
 */
@Service
public class ProductLinkService {
  static final String CHANNEL_WEIDIAN = "WEIDIAN";
  static final String TCR_TEMPLATE_CODE = "TCRM_RELAY";
  static final int MAX_CONVERT_ITEMS = 500;
  static final int MAX_RESOLVE_ITEMS = 50;

  private final CopyTokenApi tokens;
  private final CopyTargetShopApi targetShops;
  private final CopyJobMapper jobs;
  private final CopyJobItemMapper items;
  private final ShopMapper shops;
  private final KolItemMapper kolItems;
  private final ProductLinkRateLimiter rates;
  private final CopyJobWorker worker;
  private final CopyJobProgress progress;
  private final WeidianItemCopyApi copies;
  private final JdbcTemplate jdbc;
  private final ProductMapper products;
  private final CopyJobFailureRecorder failures;
  private final SecureRandom random;
  private final SystemConfigApi configs;

  @Autowired
  public ProductLinkService(
      CopyTokenApi tokens,
      CopyTargetShopApi targetShops,
      CopyJobMapper jobs,
      CopyJobItemMapper items,
      ShopMapper shops,
      KolItemMapper kolItems,
      ProductLinkRateLimiter rates,
      CopyJobWorker worker,
      CopyJobProgress progress,
      WeidianItemCopyApi copies,
      JdbcTemplate jdbc,
      ProductMapper products,
      CopyJobFailureRecorder failures,
      SystemConfigApi configs) {
    this.tokens = tokens;
    this.targetShops = targetShops;
    this.jobs = jobs;
    this.items = items;
    this.shops = shops;
    this.kolItems = kolItems;
    this.rates = rates;
    this.worker = worker;
    this.progress = progress;
    this.copies = copies;
    this.jdbc = jdbc;
    this.products = products;
    this.failures = failures;
    this.configs = configs;
    this.random = new SecureRandom();
  }

  ProductLinkService(
      CopyTokenApi tokens,
      CopyTargetShopApi targetShops,
      CopyJobMapper jobs,
      CopyJobItemMapper items,
      ShopMapper shops,
      KolItemMapper kolItems,
      ProductLinkRateLimiter rates,
      CopyJobWorker worker,
      CopyJobProgress progress,
      WeidianItemCopyApi copies,
      JdbcTemplate jdbc,
      ProductMapper products,
      CopyJobFailureRecorder failures,
      String linkBaseUrl,
      SecureRandom random) {
    this.tokens = tokens;
    this.targetShops = targetShops;
    this.jobs = jobs;
    this.items = items;
    this.shops = shops;
    this.kolItems = kolItems;
    this.rates = rates;
    this.worker = worker;
    this.progress = progress;
    this.copies = copies;
    this.jdbc = jdbc;
    this.products = products;
    this.failures = failures;
    this.configs =
        new SystemConfigApi() {
          @Override
          public String getValue(String key) {
            return linkBaseUrl == null ? "" : linkBaseUrl.replaceAll("/+$", "");
          }

          @Override
          public int getInt(String key) {
            return 0;
          }
        };
    this.random = random;
  }

  ProductLinkService(
      CopyTokenApi tokens,
      CopyJobMapper jobs,
      CopyJobItemMapper items,
      ShopMapper shops,
      KolItemMapper kolItems,
      ProductLinkRateLimiter rates,
      CopyJobWorker worker,
      CopyJobProgress progress,
      WeidianItemCopyApi copies,
      JdbcTemplate jdbc,
      String linkBaseUrl,
      SecureRandom random) {
    this(
        tokens,
        null,
        jobs,
        items,
        shops,
        kolItems,
        rates,
        worker,
        progress,
        copies,
        jdbc,
        null,
        null,
        linkBaseUrl,
        random);
  }

  public List<LinkTemplateView> templates(String channelCode) {
    String channel =
        channelCode == null || channelCode.isBlank()
            ? CHANNEL_WEIDIAN
            : channelCode.strip().toUpperCase(Locale.ROOT);
    List<LinkTemplateView> out = new ArrayList<>();
    out.add(
        new LinkTemplateView(
            "tcrm-relay",
            TCR_TEMPLATE_CODE,
            "SparkTrack 中转",
            configs.getValue("url.link") + "/i/{PRODUCT_ID}",
            channel,
            0));
    // Merge enabled company templates, dedupe by template_url (system scope: public platform URLs).
    List<LinkTemplateView> platforms =
        TenantScope.callSystem(
            "list public link templates for product-link scripts",
            () ->
                jdbc.query(
                    """
                    SELECT id, template_code, template_name, template_url, channel_code, sort_order
                    FROM crm_link_template
                    WHERE is_deleted = FALSE AND status = 1
                      AND (CAST(? AS VARCHAR) IS NULL OR channel_code = ?)
                    ORDER BY sort_order ASC, id ASC
                    """,
                    (rs, i) ->
                        new LinkTemplateView(
                            Long.toString(rs.getLong("id")),
                            rs.getString("template_code"),
                            rs.getString("template_name"),
                            rs.getString("template_url"),
                            rs.getString("channel_code"),
                            rs.getInt("sort_order")),
                    channel,
                    channel));
    Set<String> seenUrls = new HashSet<>();
    seenUrls.add(configs.getValue("url.link") + "/i/{PRODUCT_ID}");
    for (LinkTemplateView row : platforms) {
      if (row.templateUrl() == null || !seenUrls.add(row.templateUrl())) {
        continue;
      }
      out.add(row);
    }
    return out;
  }

  @Transactional(rollbackFor = Exception.class)
  public ConvertResult convert(ConvertCommand command) {
    if (command.items() == null || command.items().isEmpty()) {
      throw ProductLinkMessages.chinese(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (command.items().size() > MAX_CONVERT_ITEMS) {
      throw ProductLinkMessages.chinese(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    CopyTokenGrantDTO grant = authenticate(command.token());
    rates.checkConvertToken(grant.tokenId());
    return TenantScope.callCompany(
        grant.tenantId(),
        () -> {
          // Deduplicate by sourceProductId within the job (first wins).
          Map<String, ConvertItem> unique = new LinkedHashMap<>();
          for (ConvertItem item : command.items()) {
            if (item == null || blank(item.sourceProductId())) {
              continue;
            }
            String id = item.sourceProductId().strip();
            if (!id.matches("^[0-9]{1,32}$")) {
              continue;
            }
            unique.putIfAbsent(id, item);
          }
          if (unique.isEmpty()) {
            throw ProductLinkMessages.chinese(ErrorCode.COMMON_VALIDATION_FAILED);
          }
          long targetShopId = resolveTargetShop(grant, unique.size());
          Instant now = Instant.now();
          String jobCode = newJobCode();
          CopyJobDO job = new CopyJobDO();
          job.setTenantId(grant.tenantId());
          job.setKolId(grant.kolId());
          job.setTokenId(grant.tokenId());
          job.setJobCode(jobCode);
          job.setCallerPlatform(
              blank(command.callerPlatform()) ? "unknown" : command.callerPlatform().strip());
          job.setTargetShopId(targetShopId);
          job.setTargetChannel(channelOf(grant.targetChannel()));
          job.setStatus(CopyJobDO.STATUS_PROCESSING);
          job.setTotalCount(unique.size());
          job.setSuccessCount(0);
          job.setFailCount(0);
          job.setCreateTime(now);
          job.setUpdateTime(now);
          jobs.insert(job);
          for (ConvertItem item : unique.values()) {
            CopyJobItemDO row = new CopyJobItemDO();
            row.setJobId(job.getId());
            row.setTenantId(grant.tenantId());
            String channel =
                blank(item.sourceChannelCode())
                    ? CHANNEL_WEIDIAN
                    : item.sourceChannelCode().strip().toUpperCase(Locale.ROOT);
            row.setSourceChannel(channel);
            row.setSourceProductId(item.sourceProductId().strip());
            if (CHANNEL_WEIDIAN.equals(channel)) {
              row.setSourceShopId(uniqueSourceShop(item.sourceProductId().strip()));
            }
            row.setRuleId(blank(item.ruleId()) ? null : item.ruleId().strip());
            if (!CHANNEL_WEIDIAN.equals(channel)) {
              row.setStatus(CopyJobItemDO.STATUS_FAILED);
              row.setErrorMessage(ProductLinkMessages.UNSUPPORTED_CHANNEL);
            } else {
              row.setStatus(CopyJobItemDO.STATUS_PENDING);
            }
            row.setUpdateTime(now);
            items.insert(row);
          }
          progress.recountAndMaybeFinish(job.getId());
          worker.submit(job.getId());
          return new ConvertResult(jobCode);
        });
  }

  public ResolveResult resolve(String jobCode, ResolveCommand command) {
    if (command.items() == null || command.items().isEmpty()) {
      throw ProductLinkMessages.chinese(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    if (command.items().size() > MAX_RESOLVE_ITEMS) {
      throw ProductLinkMessages.chinese(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    CopyTokenGrantDTO grant = authenticate(command.token());
    rates.checkResolveToken(grant.tokenId());
    CopyJobDO job =
        TenantScope.callSystem(
            "locate copy job by code for items/resolve", () -> requireJob(jobCode));
    if (job.getKolId() != grant.kolId() || job.getTenantId() != grant.tenantId()) {
      throw ProductLinkMessages.chinese(ErrorCode.COPY_TOKEN_INVALID);
    }
    return TenantScope.callCompany(job.getTenantId(), () -> buildResolve(job, command.items()));
  }

  public JobView getJob(String jobCode) {
    CopyJobDO job = TenantScope.callSystem("locate copy job by code", () -> requireJob(jobCode));
    return TenantScope.callCompany(
        job.getTenantId(),
        () ->
            new JobView(
                job.getJobCode(),
                job.getStatus(),
                job.getTotalCount() == null ? 0 : job.getTotalCount(),
                job.getSuccessCount() == null ? 0 : job.getSuccessCount(),
                job.getFailCount() == null ? 0 : job.getFailCount()));
  }

  public List<JobItemView> listItems(String jobCode) {
    CopyJobDO job =
        TenantScope.callSystem("locate copy job by code for items", () -> requireJob(jobCode));
    return TenantScope.callCompany(
        job.getTenantId(),
        () -> {
          ShopDO target = shops.selectById(job.getTargetShopId());
          String storeId = target == null ? "" : Objects.toString(target.getWeidianShopId(), "");
          List<JobItemView> out = new ArrayList<>();
          for (CopyJobItemDO row : items.listByJob(job.getId())) {
            out.add(toJobItemView(row, storeId));
          }
          return out;
        });
  }

  private ResolveResult buildResolve(CopyJobDO job, List<ResolveItem> requested) {
    ShopDO target = shops.selectById(job.getTargetShopId());
    String storeId = target == null ? "" : Objects.toString(target.getWeidianShopId(), "");
    Map<String, CopyJobItemDO> bySource = new HashMap<>();
    for (CopyJobItemDO row : items.listByJob(job.getId())) {
      bySource.put(key(row.getSourceChannel(), row.getSourceProductId()), row);
    }
    boolean pending = false;
    List<ResolveItemView> views = new ArrayList<>();
    for (ResolveItem req : requested) {
      String channel =
          blank(req.sourceChannelCode())
              ? CHANNEL_WEIDIAN
              : req.sourceChannelCode().strip().toUpperCase(Locale.ROOT);
      String productId = req.sourceProductId() == null ? "" : req.sourceProductId().strip();
      CopyJobItemDO row = bySource.get(key(channel, productId));
      if (row == null) {
        // already_applied: source id is already this KOL's exclusive item id
        if (CHANNEL_WEIDIAN.equals(channel) && productId.matches("^[0-9]{1,32}$")) {
          KolItemDO existing =
              kolItems.selectOne(
                  new LambdaQueryWrapper<KolItemDO>()
                      .eq(KolItemDO::getKolId, job.getKolId())
                      .eq(KolItemDO::getWeidianItemId, productId)
                      .isNull(KolItemDO::getValidTo)
                      .last("LIMIT 1"));
          if (existing != null) {
            views.add(
                new ResolveItemView(channel, productId, "already_applied", null, null, null, null));
            continue;
          }
        }
        views.add(new ResolveItemView(channel, productId, "not_found", null, null, null, null));
        continue;
      }
      if (CopyJobItemDO.STATUS_PENDING.equals(row.getStatus())) {
        pending = true;
        views.add(new ResolveItemView(channel, productId, "pending", null, null, null, null));
        continue;
      }
      if (CopyJobItemDO.STATUS_SUCCESS.equals(row.getStatus())) {
        views.add(
            new ResolveItemView(
                channel,
                productId,
                "success",
                CHANNEL_WEIDIAN,
                row.getTargetItemId(),
                storeId,
                null));
        continue;
      }
      // failed — conflict uses a dedicated resolve status when the message matches
      String status =
          ProductLinkMessages.SOURCE_CONFLICT.equals(row.getErrorMessage()) ? "conflict" : "failed";
      views.add(
          new ResolveItemView(channel, productId, status, null, null, null, row.getErrorMessage()));
    }
    // Script accepts ready / preparing / job_pending; we never emit preparing.
    String status = pending ? "job_pending" : "ready";
    // When still processing overall, also job_pending even if this batch has no pending rows yet.
    if (!pending && CopyJobDO.STATUS_PROCESSING.equals(job.getStatus())) {
      status = "job_pending";
    }
    String resultVersion =
        job.getUpdateTime() == null
            ? Long.toString(Instant.now().toEpochMilli())
            : Long.toString(job.getUpdateTime().toEpochMilli());
    // ready requires non-empty resultVersion string (script check).
    return new ResolveResult(job.getJobCode(), status, resultVersion, views);
  }

  private JobItemView toJobItemView(CopyJobItemDO row, String storeId) {
    return new JobItemView(
        row.getSourceChannel(),
        row.getSourceProductId(),
        row.getStatus(),
        CopyJobItemDO.STATUS_SUCCESS.equals(row.getStatus()) ? CHANNEL_WEIDIAN : null,
        row.getTargetItemId(),
        CopyJobItemDO.STATUS_SUCCESS.equals(row.getStatus()) ? storeId : null,
        row.getErrorMessage());
  }

  private CopyTokenGrantDTO authenticate(String token) {
    if (blank(token)) {
      throw ProductLinkMessages.chinese(ErrorCode.COPY_TOKEN_INVALID);
    }
    try {
      return tokens.authenticate(token.strip());
    } catch (BusinessException e) {
      throw ProductLinkMessages.wrap(e);
    }
  }

  private long resolveTargetShop(CopyTokenGrantDTO grant, int quantity) {
    Long shopId = grant.targetShopId();
    if (targetShops == null) {
      if (shopId == null) {
        throw ProductLinkMessages.chinese(ErrorCode.COPY_TARGET_SHOP_MISSING);
      }
      ShopDO legacy = shops.selectById(shopId);
      if (legacy == null) {
        throw ProductLinkMessages.chinese(ErrorCode.COPY_TARGET_SHOP_MISSING);
      }
      if (!Integer.valueOf(1).equals(legacy.getStatus())) {
        throw ProductLinkMessages.chinese(ErrorCode.SHOP_DISABLED);
      }
      if (!copies.isShopAuthorized(shopId)) {
        throw ProductLinkMessages.chinese(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
      }
      return shopId;
    }
    if (shopId == null) {
      try {
        shopId =
            TenantScope.callCompany(
                grant.tenantId(),
                () -> targetShops.allocateCopyTarget(grant.targetChannel(), quantity));
      } catch (BusinessException e) {
        throw ProductLinkMessages.wrap(e);
      }
    } else {
      Long selectedShopId = shopId;
      try {
        TenantScope.runCompany(
            grant.tenantId(),
            () -> targetShops.validateCopyTarget(selectedShopId, grant.targetChannel(), quantity));
      } catch (BusinessException e) {
        throw ProductLinkMessages.wrap(e);
      }
    }
    ShopDO shop = shops.selectById(shopId);
    if (shop == null) {
      throw ProductLinkMessages.chinese(ErrorCode.COPY_TARGET_SHOP_MISSING);
    }
    if (shop.getStatus() == null || shop.getStatus() != 1) {
      throw ProductLinkMessages.chinese(ErrorCode.SHOP_DISABLED);
    }
    if (!copies.isShopAuthorized(shopId)) {
      throw ProductLinkMessages.chinese(ErrorCode.WEIDIAN_SHOP_NOT_AUTHORIZED);
    }
    return shopId;
  }

  /** One live original product for this item id. Several shops, or none, stay empty. */
  private Long uniqueSourceShop(String weidianItemId) {
    List<ProductDO> found =
        products.selectList(
            new LambdaQueryWrapper<ProductDO>().eq(ProductDO::getWeidianItemId, weidianItemId));
    if (found == null || found.isEmpty()) {
      return null;
    }
    Set<Long> shopIds = new HashSet<>();
    for (ProductDO product : found) {
      if (product.getShopId() != null) {
        shopIds.add(product.getShopId());
      }
    }
    if (shopIds.size() != 1) {
      return null;
    }
    return shopIds.iterator().next();
  }

  private static String channelOf(String channel) {
    return blank(channel) ? CHANNEL_WEIDIAN : channel.strip().toUpperCase(Locale.ROOT);
  }

  private CopyJobDO requireJob(String jobCode) {
    if (blank(jobCode)) {
      throw ProductLinkMessages.chinese(ErrorCode.COMMON_NOT_FOUND);
    }
    CopyJobDO job = jobs.findByCode(jobCode.strip());
    if (job == null) {
      throw ProductLinkMessages.chinese(ErrorCode.COMMON_NOT_FOUND);
    }
    return job;
  }

  private String newJobCode() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String key(String channel, String productId) {
    return (channel == null ? "" : channel) + ":" + (productId == null ? "" : productId);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record LinkTemplateView(
      String id,
      String templateCode,
      String templateName,
      String templateUrl,
      String channelCode,
      int sortOrder) {}

  public record ConvertCommand(String token, String callerPlatform, List<ConvertItem> items) {}

  public record ConvertItem(String sourceChannelCode, String sourceProductId, String ruleId) {}

  public record ConvertResult(String jobCode) {}

  public record ResolveCommand(String token, List<ResolveItem> items) {}

  public record ResolveItem(String sourceChannelCode, String sourceProductId) {}

  public record ResolveResult(
      String jobCode, String status, String resultVersion, List<ResolveItemView> items) {}

  public record ResolveItemView(
      String sourceChannelCode,
      String sourceProductId,
      String status,
      String targetChannelCode,
      String targetExternalProductId,
      String targetStoreId,
      String errorMessage) {}

  public record JobView(
      String jobCode, String status, int totalCount, int successCount, int failCount) {}

  public record JobItemView(
      String sourceChannelCode,
      String sourceProductId,
      String status,
      String targetChannelCode,
      String targetExternalProductId,
      String targetStoreId,
      String errorMessage) {}
}
