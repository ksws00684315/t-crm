package com.tcrm.admin.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.mapper.CopyJobItemMapper;
import com.tcrm.admin.product.mapper.CopyJobMapper;
import com.tcrm.admin.product.mapper.ShopMapper;
import com.tcrm.admin.product.model.CopyJobDO;
import com.tcrm.admin.product.model.CopyJobItemDO;
import com.tcrm.admin.product.model.ShopDO;
import com.tcrm.admin.security.api.AccountDTO;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.BusinessException;
import com.tcrm.admin.shared.ErrorCode;
import com.tcrm.admin.shared.PageResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Product-task list and detail (task 38). OWN-scope staff see only KOLs assigned to them. */
@Service
public class CopyJobQueryService {
  static final String WEIDIAN_ITEM_URL = "https://weidian.com/item.html?itemID=";

  private static final Set<String> JOB_STATUSES =
      Set.of(
          CopyJobDO.STATUS_PROCESSING,
          CopyJobDO.STATUS_COMPLETED,
          CopyJobDO.STATUS_FAILED,
          CopyJobDO.STATUS_PARTIAL_FAILED);
  private static final Pattern CHANNEL = Pattern.compile("^[A-Z][A-Z0-9_]*$");

  private final CopyJobMapper jobs;
  private final CopyJobItemMapper items;
  private final ShopMapper shops;
  private final KolApi kols;
  private final CurrentUserContext current;

  public CopyJobQueryService(
      CopyJobMapper jobs,
      CopyJobItemMapper items,
      ShopMapper shops,
      KolApi kols,
      CurrentUserContext current) {
    this.jobs = jobs;
    this.items = items;
    this.shops = shops;
    this.kols = kols;
    this.current = current;
  }

  public PageResult<CopyJobDTO> page(CopyJobQueryDTO filter) {
    AccountDTO user = current.user();
    String channel = channelFilter(filter.targetChannel());
    String status = statusFilter(filter.status());
    String prefix = jobCodePrefix(filter.jobCode());
    List<Long> ownKolIds =
        user.ownScope()
            ? kols.listByBdUserId(user.id()).stream().map(KolRefDTO::id).toList()
            : List.of();
    if (user.ownScope() && ownKolIds.isEmpty()) {
      return new PageResult<>(List.of(), 0, filter.page(), filter.size());
    }
    var query =
        new LambdaQueryWrapper<CopyJobDO>()
            .in(user.ownScope(), CopyJobDO::getKolId, ownKolIds)
            .eq(channel != null, CopyJobDO::getTargetChannel, channel)
            .eq(status != null, CopyJobDO::getStatus, status)
            .apply(prefix != null, "job_code LIKE {0} ESCAPE '\\'", prefix)
            .orderByDesc(CopyJobDO::getCreateTime)
            .orderByDesc(CopyJobDO::getId);
    var result = jobs.selectPage(new Page<>(filter.page(), filter.size()), query);
    Map<Long, KolRefDTO> kolMap = kolMap(result.getRecords());
    Map<Long, ShopDO> shopMap =
        shopMap(result.getRecords().stream().map(CopyJobDO::getTargetShopId).toList());
    return new PageResult<>(
        result.getRecords().stream().map(row -> toJob(row, kolMap, shopMap)).toList(),
        result.getTotal(),
        filter.page(),
        filter.size());
  }

  public PageResult<CopyJobItemDTO> items(long jobId, int page, int size) {
    CopyJobDO job = requireVisible(jobId);
    var result =
        items.selectPage(
            new Page<>(page, size),
            new LambdaQueryWrapper<CopyJobItemDO>()
                .eq(CopyJobItemDO::getJobId, job.getId())
                .orderByAsc(CopyJobItemDO::getId));
    Set<Long> shopIds = new HashSet<>();
    if (job.getTargetShopId() != null) {
      shopIds.add(job.getTargetShopId());
    }
    for (CopyJobItemDO row : result.getRecords()) {
      if (row.getSourceShopId() != null) {
        shopIds.add(row.getSourceShopId());
      }
    }
    Map<Long, ShopDO> shopMap = shopsById(shopIds);
    int offset = (page - 1) * size;
    List<CopyJobItemDTO> rows = new ArrayList<>();
    for (int i = 0; i < result.getRecords().size(); i++) {
      rows.add(toItem(result.getRecords().get(i), offset + i + 1, job, shopMap));
    }
    return new PageResult<>(rows, result.getTotal(), page, size);
  }

  /** Prefix pattern for {@code job_code LIKE}. Escapes {@code \}, {@code %} and {@code _}. */
  static String likePrefix(String raw) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '\\' || c == '%' || c == '_') {
        out.append('\\');
      }
      out.append(c);
    }
    out.append('%');
    return out.toString();
  }

  static String targetItemUrl(String targetItemId) {
    if (targetItemId == null || targetItemId.isBlank()) {
      return null;
    }
    return WEIDIAN_ITEM_URL + targetItemId;
  }

  private CopyJobDO requireVisible(long jobId) {
    AccountDTO user = current.user();
    CopyJobDO job = jobs.selectById(jobId);
    if (job == null) {
      throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
    }
    if (user.ownScope()) {
      List<KolRefDTO> found = kols.listByIds(Set.of(job.getKolId()));
      KolRefDTO kol = found.isEmpty() ? null : found.getFirst();
      if (kol == null || !Objects.equals(kol.bdUserId(), user.id())) {
        throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
      }
    }
    return job;
  }

  private static String jobCodePrefix(String jobCode) {
    if (jobCode == null || jobCode.isBlank()) {
      return null;
    }
    return likePrefix(jobCode.strip());
  }

  private static String statusFilter(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    String value = status.strip();
    if (!JOB_STATUSES.contains(value)) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return value;
  }

  private static String channelFilter(String channel) {
    if (channel == null || channel.isBlank()) {
      return null;
    }
    String value = channel.strip();
    if (!CHANNEL.matcher(value).matches()) {
      throw new BusinessException(ErrorCode.COMMON_VALIDATION_FAILED);
    }
    return value;
  }

  private Map<Long, KolRefDTO> kolMap(List<CopyJobDO> rows) {
    Set<Long> ids = new HashSet<>();
    for (CopyJobDO row : rows) {
      if (row.getKolId() != null) {
        ids.add(row.getKolId());
      }
    }
    Map<Long, KolRefDTO> map = new HashMap<>();
    if (ids.isEmpty()) {
      return map;
    }
    for (KolRefDTO kol : kols.listByIds(ids)) {
      map.put(kol.id(), kol);
    }
    return map;
  }

  private Map<Long, ShopDO> shopMap(List<Long> ids) {
    return shopsById(new HashSet<>(ids));
  }

  private Map<Long, ShopDO> shopsById(Set<Long> ids) {
    ids.remove(null);
    Map<Long, ShopDO> map = new HashMap<>();
    if (ids.isEmpty()) {
      return map;
    }
    for (ShopDO shop : shops.selectList(new LambdaQueryWrapper<ShopDO>().in(ShopDO::getId, ids))) {
      map.put(shop.getId(), shop);
    }
    return map;
  }

  private static CopyJobDTO toJob(
      CopyJobDO row, Map<Long, KolRefDTO> kols, Map<Long, ShopDO> shops) {
    KolRefDTO kol = kols.get(row.getKolId());
    ShopDO shop = shops.get(row.getTargetShopId());
    return new CopyJobDTO(
        row.getId(),
        row.getJobCode(),
        row.getTargetChannel(),
        row.getStatus(),
        row.getTotalCount() == null ? 0 : row.getTotalCount(),
        row.getSuccessCount() == null ? 0 : row.getSuccessCount(),
        row.getFailCount() == null ? 0 : row.getFailCount(),
        row.getErrorMessage(),
        row.getCreateTime(),
        row.getFinishTime(),
        row.getKolId(),
        kol == null ? null : kol.kolCode(),
        kol == null ? null : kol.name(),
        shop == null ? null : shop.getShopCode(),
        shop == null ? null : shop.getShopName());
  }

  private static CopyJobItemDTO toItem(
      CopyJobItemDO row, int seq, CopyJobDO job, Map<Long, ShopDO> shops) {
    ShopDO source = shops.get(row.getSourceShopId());
    ShopDO target = shops.get(job.getTargetShopId());
    return new CopyJobItemDTO(
        seq,
        row.getSourceChannel(),
        row.getSourceProductId(),
        row.getSourceShopId(),
        source == null ? null : source.getShopName(),
        source == null ? null : source.getWeidianShopId(),
        job.getTargetChannel(),
        job.getTargetShopId(),
        target == null ? null : target.getShopName(),
        row.getTargetItemId(),
        targetItemUrl(row.getTargetItemId()),
        row.getStatus(),
        row.getErrorMessage());
  }
}
