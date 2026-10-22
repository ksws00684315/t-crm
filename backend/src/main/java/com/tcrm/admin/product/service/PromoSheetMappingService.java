package com.tcrm.admin.product.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.KolMappingsChangedEvent;
import com.tcrm.admin.product.api.PromoSheetClassification;
import com.tcrm.admin.product.api.PromoSheetClassification.ConflictItem;
import com.tcrm.admin.product.api.PromoSheetClassification.NewItem;
import com.tcrm.admin.product.api.PromoSheetClassification.SkipSample;
import com.tcrm.admin.product.api.PromoSheetConfirmResult;
import com.tcrm.admin.product.api.PromoSheetItemApi;
import com.tcrm.admin.product.api.PromoSheetRowDTO;
import com.tcrm.admin.product.api.PromoSheetSkipReason;
import com.tcrm.admin.product.api.SheetLinkParser;
import com.tcrm.admin.product.api.ShopAccessApi;
import com.tcrm.admin.product.api.ShopAccessDTO;
import com.tcrm.admin.product.mapper.KolItemMapper;
import com.tcrm.admin.product.mapper.ProductMapper;
import com.tcrm.admin.product.model.KolItemDO;
import com.tcrm.admin.product.model.KolItemOwnerRow;
import com.tcrm.admin.product.model.KolItemShopRow;
import com.tcrm.admin.product.model.PromoProductRow;
import com.tcrm.admin.security.api.CurrentUserContext;
import com.tcrm.admin.shared.TransactionalEvents;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Promo-sheet classification and confirmation against {@code crm_kol_item} (task 26 §3.3, ADR-024).
 * Pure classification never writes; confirmation re-runs it inside one transaction that also holds
 * the shop-item advisory locks, so a racing import or upload cannot take the same item.
 */
@Service
public class PromoSheetMappingService implements PromoSheetItemApi {
  /** Skip samples kept for the preview response (task 26 §3.3). */
  static final int MAX_SKIP_SAMPLES = 50;

  /** Shop-item lookups per query; a promo sheet holds hundreds to a few thousand items. */
  private static final int ITEM_BATCH = 500;

  private final KolItemMapper items;
  private final ProductMapper products;
  private final ShopAccessApi shops;
  private final KolApi kols;
  private final CurrentUserContext current;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;

  public PromoSheetMappingService(
      KolItemMapper items,
      ProductMapper products,
      ShopAccessApi shops,
      KolApi kols,
      CurrentUserContext current,
      TransactionTemplate tx,
      ApplicationEventPublisher events) {
    this.items = items;
    this.products = products;
    this.shops = shops;
    this.kols = kols;
    this.current = current;
    this.tx = tx;
    this.events = events;
  }

  /** One deduplicated shop item of the sheet with everything classification needs. */
  private record Candidate(
      long shopId, String shopName, String shopWeidianId, String weidianItemId, String itemName) {}

  /** Shop items plus skip bookkeeping shared by classify and confirm. */
  private record Parsed(
      List<Candidate> candidates,
      int linkRows,
      int duplicates,
      Map<PromoSheetSkipReason, Integer> skipCounts,
      List<SkipSample> skipSamples) {}

  @Override
  public PromoSheetClassification classify(Long kolId, List<PromoSheetRowDTO> rows) {
    Parsed parsed = parse(rows);
    Map<String, Long> owners = ownersByItem(parsed.candidates());
    Map<String, PromoProductRow> productRows = productRowsByShopItem(parsed.candidates());
    Set<String> sheetItems = sheetItemKeys(parsed.candidates());

    int toAdd = 0;
    int existing = 0;
    int conflicts = 0;
    List<NewItem> newItems = new ArrayList<>();
    List<ConflictItem> conflictItems = new ArrayList<>();
    Set<Long> ownerIds = new HashSet<>();
    for (Candidate candidate : parsed.candidates()) {
      String key = candidate.shopWeidianId() + ":" + candidate.weidianItemId();
      Long owner = owners.get(key);
      if (owner == null) {
        toAdd++;
        PromoProductRow product = productRows.get(key);
        newItems.add(
            new NewItem(
                candidate.shopName(),
                candidate.shopWeidianId(),
                candidate.weidianItemId(),
                candidate.itemName(),
                product == null ? null : product.getProductCode()));
      } else if (owner.equals(kolId)) {
        existing++;
      } else {
        conflicts++;
        ownerIds.add(owner);
      }
    }
    Map<Long, KolRefDTO> ownerKols = kolMap(ownerIds);
    for (Candidate candidate : parsed.candidates()) {
      String key = candidate.shopWeidianId() + ":" + candidate.weidianItemId();
      Long owner = owners.get(key);
      if (owner == null || owner.equals(kolId)) {
        continue;
      }
      KolRefDTO ownerKol = ownerKols.get(owner);
      conflictItems.add(
          new ConflictItem(
              candidate.shopWeidianId(),
              candidate.weidianItemId(),
              candidate.itemName(),
              ownerKol == null ? null : ownerKol.kolCode(),
              ownerKol == null ? null : ownerKol.name()));
    }
    int notInSheet = notInSheet(kolId, sheetItems);
    return new PromoSheetClassification(
        parsed.linkRows(),
        parsed.duplicates(),
        toAdd,
        existing,
        conflicts,
        notInSheet,
        parsed.skipCounts(),
        parsed.skipSamples(),
        newItems,
        conflictItems);
  }

  @Override
  public PromoSheetConfirmResult confirm(Long kolId, List<PromoSheetRowDTO> rows) {
    Parsed parsed = parse(rows);
    Long actorId = current.id();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    return tx.execute(
        status -> {
          // Hold every shop-item lock before re-reading, so the checks below are stable against a
          // racing import or another sheet upload for the same items.
          for (Candidate candidate : parsed.candidates()) {
            products.lockShopItem(
                ProductRules.shopItemLockKey(candidate.shopId(), candidate.weidianItemId()));
          }
          Map<String, Long> owners = ownersByItem(parsed.candidates());
          Map<String, PromoProductRow> productRows = productRowsByShopItem(parsed.candidates());
          List<Candidate> additions = new ArrayList<>();
          int existing = 0;
          List<Candidate> conflictsNow = new ArrayList<>();
          Set<Long> ownerIds = new HashSet<>();
          for (Candidate candidate : parsed.candidates()) {
            String key = candidate.shopWeidianId() + ":" + candidate.weidianItemId();
            Long owner = owners.get(key);
            if (owner == null) {
              additions.add(candidate);
            } else if (owner.equals(kolId)) {
              existing++;
            } else {
              conflictsNow.add(candidate);
              ownerIds.add(owner);
            }
          }
          for (Candidate candidate : additions) {
            KolItemDO mapping = new KolItemDO();
            mapping.setKolId(kolId);
            PromoProductRow product =
                productRows.get(candidate.shopWeidianId() + ":" + candidate.weidianItemId());
            mapping.setProductId(product == null ? null : product.getProductId());
            mapping.setShopId(candidate.shopId());
            mapping.setWeidianItemId(candidate.weidianItemId());
            mapping.setItemName(candidate.itemName());
            mapping.setSource("SHEET_UPLOAD");
            mapping.setValidFrom(now);
            mapping.setCreateBy(actorId);
            mapping.setUpdateBy(actorId);
            KolItemCodes.insertNew(items, mapping);
          }
          if (!additions.isEmpty()) {
            // Registered in this transaction and applied to the link index afterwards (ADR-013);
            // the KOL scope also covers mappings without an original product.
            TransactionalEvents.publish(events, new KolMappingsChangedEvent(kolId));
          }
          Map<Long, KolRefDTO> ownerKols = kolMap(ownerIds);
          List<ConflictItem> conflictItems =
              conflictsNow.stream()
                  .map(
                      candidate -> {
                        Long owner =
                            owners.get(candidate.shopWeidianId() + ":" + candidate.weidianItemId());
                        KolRefDTO ownerKol = ownerKols.get(owner);
                        return new ConflictItem(
                            candidate.shopWeidianId(),
                            candidate.weidianItemId(),
                            candidate.itemName(),
                            ownerKol == null ? null : ownerKol.kolCode(),
                            ownerKol == null ? null : ownerKol.name());
                      })
                  .toList();
          int notInSheet = notInSheet(kolId, sheetItemKeys(parsed.candidates()));
          return new PromoSheetConfirmResult(
              additions.size(),
              parsed.duplicates(),
              existing,
              conflictsNow.size(),
              notInSheet,
              parsed.skipCounts(),
              conflictItems);
        });
  }

  /** Turns parsed rows into deduplicated candidates and skip bookkeeping. */
  private Parsed parse(List<PromoSheetRowDTO> rows) {
    Map<String, ShopAccessDTO> registeredShops = new HashMap<>();
    for (ShopAccessDTO shop : shops.listShops()) {
      if (Integer.valueOf(1).equals(shop.status())) {
        registeredShops.put(shop.weidianShopId(), shop);
      }
    }
    Map<PromoSheetSkipReason, Integer> skipCounts = new EnumMap<>(PromoSheetSkipReason.class);
    List<SkipSample> skipSamples = new ArrayList<>();
    Set<String> seenItems = new HashSet<>();
    List<Candidate> candidates = new ArrayList<>();
    int duplicates = 0;
    for (PromoSheetRowDTO row : rows) {
      if (row.inSheetJump() && row.link() == null) {
        count(skipCounts, PromoSheetSkipReason.IN_SHEET);
        sample(skipSamples, row, PromoSheetSkipReason.IN_SHEET);
        continue;
      }
      String decoded = SheetLinkParser.decode(row.link());
      if (SheetLinkParser.isOtherPlatform(decoded)) {
        count(skipCounts, PromoSheetSkipReason.NON_WEIDIAN);
        sample(skipSamples, row, PromoSheetSkipReason.NON_WEIDIAN);
        continue;
      }
      String itemId = SheetLinkParser.itemId(decoded);
      if (itemId == null) {
        count(skipCounts, PromoSheetSkipReason.NO_ITEM_ID);
        sample(skipSamples, row, PromoSheetSkipReason.NO_ITEM_ID);
        continue;
      }
      String shopNumber = SheetLinkParser.shopId(decoded);
      ShopAccessDTO shop = shopNumber == null ? null : registeredShops.get(shopNumber);
      if (shop == null) {
        count(skipCounts, PromoSheetSkipReason.SHOP_NOT_REGISTERED);
        sample(skipSamples, row, PromoSheetSkipReason.SHOP_NOT_REGISTERED);
        continue;
      }
      if (!seenItems.add(shop.id() + ":" + itemId)) {
        duplicates++;
        continue;
      }
      candidates.add(
          new Candidate(shop.id(), shop.name(), shop.weidianShopId(), itemId, row.name()));
    }
    return new Parsed(candidates, rows.size(), duplicates, skipCounts, List.copyOf(skipSamples));
  }

  /** Current mapping owners keyed by {@code shopWeidianId:itemId}, batched per shop. */
  private Map<String, Long> ownersByItem(Collection<Candidate> candidates) {
    Map<String, Long> owners = new HashMap<>();
    for (var entry : itemsPerShop(candidates).entrySet()) {
      String prefix = entry.getValue().shopWeidianId() + ":";
      for (var batch : batches(entry.getValue().itemIds())) {
        for (KolItemOwnerRow row : items.listCurrentOwners(entry.getKey(), batch)) {
          owners.put(prefix + row.getWeidianItemId(), row.getKolId());
        }
      }
    }
    return owners;
  }

  /** Original products keyed by {@code shopWeidianId:itemId}, batched per shop. */
  private Map<String, PromoProductRow> productRowsByShopItem(Collection<Candidate> candidates) {
    Map<String, PromoProductRow> found = new HashMap<>();
    for (var entry : itemsPerShop(candidates).entrySet()) {
      String prefix = entry.getValue().shopWeidianId() + ":";
      for (var batch : batches(entry.getValue().itemIds())) {
        for (PromoProductRow row : products.listByShopItems(entry.getKey(), batch)) {
          found.put(prefix + row.getWeidianItemId(), row);
        }
      }
    }
    return found;
  }

  /** The sheet's items grouped by shop, in insertion order. */
  private static Map<Long, ShopItems> itemsPerShop(Collection<Candidate> candidates) {
    Map<Long, ShopItems> perShop = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      perShop
          .computeIfAbsent(candidate.shopId(), id -> new ShopItems(candidate.shopWeidianId()))
          .itemIds()
          .add(candidate.weidianItemId());
    }
    return perShop;
  }

  private record ShopItems(String shopWeidianId, List<String> itemIds) {
    ShopItems(String shopWeidianId) {
      this(shopWeidianId, new ArrayList<>());
    }
  }

  /** Sheet shop items as {@code shopWeidianId:itemId} keys. */
  private static Set<String> sheetItemKeys(List<Candidate> candidates) {
    Set<String> keys = new HashSet<>();
    for (Candidate candidate : candidates) {
      keys.add(candidate.shopWeidianId() + ":" + candidate.weidianItemId());
    }
    return keys;
  }

  /** Current mappings of the KOL whose shop item did not appear in the sheet. */
  private int notInSheet(Long kolId, Set<String> sheetItems) {
    int missing = 0;
    for (KolItemShopRow row : items.listCurrentItemsWithShop(kolId)) {
      if (!sheetItems.contains(row.getWeidianShopId() + ":" + row.getWeidianItemId())) {
        missing++;
      }
    }
    return missing;
  }

  private Map<Long, KolRefDTO> kolMap(Set<Long> ids) {
    Map<Long, KolRefDTO> map = new HashMap<>();
    if (ids.isEmpty()) {
      return map;
    }
    for (KolRefDTO kol : kols.listByIds(ids)) {
      map.put(kol.id(), kol);
    }
    return map;
  }

  private static void count(
      Map<PromoSheetSkipReason, Integer> counts, PromoSheetSkipReason reason) {
    counts.merge(reason, 1, Integer::sum);
  }

  private static void sample(
      List<SkipSample> samples, PromoSheetRowDTO row, PromoSheetSkipReason reason) {
    if (samples.size() < MAX_SKIP_SAMPLES) {
      samples.add(new SkipSample(row.sheet(), row.row(), reason));
    }
  }

  private static <T> List<List<T>> batches(List<T> values) {
    List<List<T>> batches = new ArrayList<>();
    for (int from = 0; from < values.size(); from += ITEM_BATCH) {
      batches.add(values.subList(from, Math.min(values.size(), from + ITEM_BATCH)));
    }
    return batches;
  }
}
