package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tcrm.admin.kol.api.CopyTokenApi;
import com.tcrm.admin.product.api.KolItemChangedEvent;
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
import com.tcrm.admin.shared.TenantScope;
import com.tcrm.admin.shared.TransactionalEvents;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded pool that processes copy-job items sequentially within each job (task 17b). On restart,
 * unfinished jobs are resumed (pending items kept); see {@link #run}.
 */
@Component
public class CopyJobWorker implements ApplicationRunner {
  private static final Logger LOG = LoggerFactory.getLogger(CopyJobWorker.class);

  private final CopyJobMapper jobs;
  private final CopyJobItemMapper items;
  private final ShopMapper shops;
  private final ProductMapper products;
  private final KolItemMapper kolItems;
  private final ProductCodeGenerator codes;
  private final CopyTokenApi tokens;
  private final WeidianItemCopyApi copies;
  private final CopyJobProgress progress;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate tx;
  private final ExecutorService executor;

  @Autowired
  public CopyJobWorker(
      CopyJobMapper jobs,
      CopyJobItemMapper items,
      ShopMapper shops,
      ProductMapper products,
      KolItemMapper kolItems,
      ProductCodeGenerator codes,
      CopyTokenApi tokens,
      WeidianItemCopyApi copies,
      CopyJobProgress progress,
      ApplicationEventPublisher events,
      TransactionTemplate tx,
      @Value("${crm.product-links.worker-threads:2}") int threads,
      @Value("${crm.product-links.worker-queue:200}") int queue) {
    this.jobs = jobs;
    this.items = items;
    this.shops = shops;
    this.products = products;
    this.kolItems = kolItems;
    this.codes = codes;
    this.tokens = tokens;
    this.copies = copies;
    this.progress = progress;
    this.events = events;
    this.tx = tx;
    this.executor =
        new ThreadPoolExecutor(
            Math.max(1, threads),
            Math.max(1, threads),
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, queue)),
            namedThreads(),
            new ThreadPoolExecutor.CallerRunsPolicy());
  }

  /** Test constructor with an injected executor. */
  CopyJobWorker(
      CopyJobMapper jobs,
      CopyJobItemMapper items,
      ShopMapper shops,
      ProductMapper products,
      KolItemMapper kolItems,
      ProductCodeGenerator codes,
      CopyTokenApi tokens,
      WeidianItemCopyApi copies,
      CopyJobProgress progress,
      ApplicationEventPublisher events,
      TransactionTemplate tx,
      ExecutorService executor) {
    this.jobs = jobs;
    this.items = items;
    this.shops = shops;
    this.products = products;
    this.kolItems = kolItems;
    this.codes = codes;
    this.tokens = tokens;
    this.copies = copies;
    this.progress = progress;
    this.events = events;
    this.tx = tx;
    this.executor = executor;
  }

  public void submit(long jobId) {
    executor.execute(() -> processJob(jobId));
  }

  @Override
  public void run(ApplicationArguments args) {
    List<Long> pending =
        TenantScope.callSystem(
            "resume unfinished copy jobs after restart", () -> jobs.listProcessingIds(500));
    for (Long id : pending) {
      LOG.info("Resuming copy job {}", id);
      submit(id);
    }
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
  }

  void processJob(long jobId) {
    CopyJobDO job =
        TenantScope.callSystem("load copy job for worker", () -> jobs.selectById(jobId));
    if (job == null || !CopyJobDO.STATUS_PROCESSING.equals(job.getStatus())) {
      return;
    }
    TenantScope.runCompany(job.getTenantId(), () -> processInCompany(job));
  }

  private void processInCompany(CopyJobDO job) {
    List<CopyJobItemDO> pending = items.listPendingByJob(job.getId());
    for (CopyJobItemDO row : pending) {
      try {
        tx.executeWithoutResult(status -> processOne(job, row));
      } catch (RuntimeException e) {
        LOG.warn(
            "Copy job item {} failed: {}",
            row.getId(),
            e instanceof BusinessException be ? be.getErrorCode() : e.getClass().getSimpleName());
        markFailed(row.getId(), failMessage(e));
      }
      progress.recountAndMaybeFinish(job.getId());
    }
    progress.recountAndMaybeFinish(job.getId());
  }

  private void processOne(CopyJobDO job, CopyJobItemDO row) {
    // Re-read under company scope in case another worker finished it.
    CopyJobItemDO fresh = items.selectById(row.getId());
    if (fresh == null || !CopyJobItemDO.STATUS_PENDING.equals(fresh.getStatus())) {
      return;
    }
    if (!ProductLinkService.CHANNEL_WEIDIAN.equals(fresh.getSourceChannel())) {
      markFailed(fresh.getId(), ProductLinkMessages.UNSUPPORTED_CHANNEL);
      return;
    }
    // already copied for this kol+product?
    SourceLookup source = findSource(fresh.getSourceProductId());
    if (source.conflict()) {
      markFailed(fresh.getId(), ProductLinkMessages.SOURCE_CONFLICT);
      return;
    }
    if (source.product() == null) {
      markFailed(fresh.getId(), ProductLinkMessages.SOURCE_NOT_IN_SHOPS);
      return;
    }
    ProductDO product = source.product();
    KolItemDO existing = kolItems.findCurrentByKolProduct(job.getKolId(), product.getId());
    if (existing != null) {
      markSuccess(fresh.getId(), existing.getWeidianItemId());
      return;
    }
    if (!tokens.consume(job.getTokenId(), 1)) {
      markFailed(fresh.getId(), ProductLinkMessages.QUOTA_INSUFFICIENT);
      return;
    }
    String newItemId;
    try {
      newItemId =
          copies.copy(product.getShopId(), product.getWeidianItemId(), job.getTargetShopId());
    } catch (RuntimeException e) {
      markFailed(fresh.getId(), failMessage(e));
      return;
    }
    Instant now = Instant.now();
    KolItemDO neu = new KolItemDO();
    neu.setKolId(job.getKolId());
    neu.setProductId(product.getId());
    neu.setShopId(job.getTargetShopId());
    neu.setWeidianItemId(newItemId);
    neu.setValidFrom(now);
    neu.setCreateTime(now);
    neu.setUpdateTime(now);
    neu.setTenantId(job.getTenantId());
    kolItems.insert(neu);
    TransactionalEvents.publish(events, new KolItemChangedEvent(job.getKolId(), product.getId()));
    markSuccess(fresh.getId(), newItemId);
  }

  private SourceLookup findSource(String weidianItemId) {
    List<ShopDO> enabled =
        shops.selectList(new LambdaQueryWrapper<ShopDO>().eq(ShopDO::getStatus, 1));
    if (enabled.isEmpty()) {
      return SourceLookup.missing();
    }
    List<Long> shopIds = enabled.stream().map(ShopDO::getId).toList();
    List<ProductDO> found =
        products.selectList(
            new LambdaQueryWrapper<ProductDO>()
                .eq(ProductDO::getWeidianItemId, weidianItemId)
                .in(ProductDO::getShopId, shopIds));
    if (!found.isEmpty()) {
      long distinctShops = found.stream().map(ProductDO::getShopId).distinct().count();
      if (distinctShops > 1) {
        return SourceLookup.conflicted();
      }
      ProductDO product = found.getFirst();
      boolean enabledShop = enabled.stream().anyMatch(s -> s.getId().equals(product.getShopId()));
      return enabledShop ? SourceLookup.found(product) : SourceLookup.missing();
    }
    // Not registered: probe each enabled shop via Weidian item.get; register the first hit.
    List<ShopDO> hits = new ArrayList<>();
    WeidianItemCopyApi.ProbedItem hitItem = null;
    for (ShopDO shop : enabled) {
      var probed = copies.probe(shop.getId(), weidianItemId);
      if (probed.isPresent()) {
        hits.add(shop);
        hitItem = probed.get();
      }
    }
    if (hits.isEmpty()) {
      return SourceLookup.missing();
    }
    if (hits.size() > 1) {
      return SourceLookup.conflicted();
    }
    ShopDO shop = hits.getFirst();
    ProductDO registered =
        ensureProduct(
            shop.getId(),
            weidianItemId,
            hitItem == null ? null : hitItem.name(),
            hitItem == null ? null : hitItem.price());
    return SourceLookup.found(registered);
  }

  private ProductDO ensureProduct(
      long shopId, String weidianItemId, String name, BigDecimal price) {
    ProductDO existing =
        products.selectOne(
            new LambdaQueryWrapper<ProductDO>()
                .eq(ProductDO::getShopId, shopId)
                .eq(ProductDO::getWeidianItemId, weidianItemId)
                .last("LIMIT 1"));
    if (existing != null) {
      return existing;
    }
    Instant now = Instant.now();
    ProductDO product = new ProductDO();
    product.setProductCode(codes.next());
    product.setShopId(shopId);
    product.setWeidianItemId(weidianItemId);
    product.setName(name == null || name.isBlank() ? weidianItemId : name);
    product.setPrice(price == null ? BigDecimal.ZERO : price);
    product.setPriceCurrency("CNY");
    product.setStatus(1);
    product.setCreateTime(now);
    product.setUpdateTime(now);
    product.setDeleted(false);
    products.insert(product);
    return products.selectById(product.getId());
  }

  private void markSuccess(long itemId, String targetItemId) {
    items.update(
        null,
        new LambdaUpdateWrapper<CopyJobItemDO>()
            .eq(CopyJobItemDO::getId, itemId)
            .eq(CopyJobItemDO::getStatus, CopyJobItemDO.STATUS_PENDING)
            .set(CopyJobItemDO::getStatus, CopyJobItemDO.STATUS_SUCCESS)
            .set(CopyJobItemDO::getTargetItemId, targetItemId)
            .set(CopyJobItemDO::getErrorMessage, null)
            .set(CopyJobItemDO::getUpdateTime, Instant.now()));
  }

  private void markFailed(long itemId, String message) {
    String safe =
        message == null
            ? ProductLinkMessages.WEIDIAN_FAILED
            : message.length() > 500 ? message.substring(0, 500) : message;
    items.update(
        null,
        new LambdaUpdateWrapper<CopyJobItemDO>()
            .eq(CopyJobItemDO::getId, itemId)
            .in(
                CopyJobItemDO::getStatus,
                List.of(CopyJobItemDO.STATUS_PENDING, CopyJobItemDO.STATUS_FAILED))
            .set(CopyJobItemDO::getStatus, CopyJobItemDO.STATUS_FAILED)
            .set(CopyJobItemDO::getErrorMessage, safe)
            .set(CopyJobItemDO::getUpdateTime, Instant.now()));
  }

  private static String failMessage(RuntimeException e) {
    if (e instanceof BusinessException be) {
      return ProductLinkMessages.zh(be.getErrorCode());
    }
    String msg = e.getMessage();
    if (msg == null || msg.isBlank()) {
      return ProductLinkMessages.WEIDIAN_FAILED;
    }
    // Never leak tokens/secrets; keep a short Chinese/English reason.
    if (msg.toLowerCase().contains("token") || msg.toLowerCase().contains("secret")) {
      return ProductLinkMessages.WEIDIAN_FAILED;
    }
    return msg.length() > 200
        ? ProductLinkMessages.WEIDIAN_FAILED + "：" + msg.substring(0, 180)
        : msg;
  }

  private static ThreadFactory namedThreads() {
    AtomicInteger sequence = new AtomicInteger();
    return work -> {
      Thread thread = new Thread(work, "copy-job-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private record SourceLookup(ProductDO product, boolean conflict) {
    static SourceLookup missing() {
      return new SourceLookup(null, false);
    }

    static SourceLookup conflicted() {
      return new SourceLookup(null, true);
    }

    static SourceLookup found(ProductDO product) {
      return new SourceLookup(product, false);
    }
  }
}
