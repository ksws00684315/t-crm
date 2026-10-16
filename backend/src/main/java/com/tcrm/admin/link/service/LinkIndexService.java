package com.tcrm.admin.link.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import com.tcrm.admin.shared.DistributedLocks;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maintains the Redis link index from current mappings (task 06 §4.3, task 06b, task 11 §4.4).
 * Incremental refreshes run under {@link #kolLock} after the outbox worker re-reads the database.
 * Each current mapping occupies a field of {@code HASH link:{kolCode}} (original product code) and
 * the same JSON value under its exclusive item ID in {@code HASH linkitem:{last two digits}}; both
 * are written and deleted together, in the same KOL lock. A field's status is 1 only when the KOL
 * is enabled, the product on sale and the shop enabled. Disabled links keep their field with status
 * 0 so the redirect service never mistakes them for unknown links.
 */
@Service
public class LinkIndexService {
  private static final Logger LOG = LoggerFactory.getLogger(LinkIndexService.class);

  /** How long a rebuild waits for one KOL's index lock before failing that KOL. */
  public static final long KOL_LOCK_WAIT_SECONDS = 120;

  private static final String KOL_LOCK_PREFIX = "link:index:kol:";

  private final KolItemApi items;
  private final KolApi kols;
  private final LinkIndexStore store;
  private final DistributedLocks locks;

  public LinkIndexService(
      KolItemApi items, KolApi kols, LinkIndexStore store, DistributedLocks locks) {
    this.items = items;
    this.kols = kols;
    this.store = store;
    this.locks = locks;
  }

  /** Redisson lock that serializes every Redis write of this KOL, including a full rebuild. */
  public static String kolLock(long kolId) {
    return KOL_LOCK_PREFIX + kolId;
  }

  /**
   * Re-reads the current mapping of the pair after it was added, replaced or closed: writes the
   * field when a current mapping exists, deletes it otherwise (task 11 §4.4). The item index drops
   * every exclusive item ID the pair ever had except the current one, so a replacement or closure
   * leaves no stale {@code linkitem} field. Returns the number of mapping fields written. Callers
   * that share the index with a rebuild must hold {@link #kolLock}.
   */
  public int refreshMapping(Long kolId, Long productId) {
    KolRefDTO kol = kol(kolId);
    if (kol == null) {
      return 0;
    }
    KolItemLinkDTO link = items.getCurrentLink(kolId, productId);
    if (link == null) {
      String productCode = items.getProductCode(productId);
      if (productCode != null) {
        store.remove(kol.kolCode(), productCode);
      }
      removeStaleItemFields(items.listItemIdsByKolProduct(kolId, productId));
      return 0;
    }
    removeStaleItemFields(items.listItemIdsByKolProduct(kolId, productId), link.weidianItemId());
    store.putAll(kol.kolCode(), fields(kol, List.of(link)));
    store.putItemFields(Map.of(link.weidianItemId(), LinkRules.indexValue(kol, link)));
    return 1;
  }

  /**
   * Rewrites every current field of the KOL after it, one of its products, one of its shops, its
   * template or its invite code changed (task 11 §4.4). The item index first drops every exclusive
   * item ID the KOL ever had — the database history, a superset of what the old {@code
   * link:{kolCode}} held — then writes the current ones. Callers that share the index with a
   * rebuild must hold {@link #kolLock}.
   */
  public int refreshKol(Long kolId) {
    KolRefDTO kol = kol(kolId);
    if (kol == null) {
      return 0;
    }
    List<KolItemLinkDTO> links = items.listCurrentLinksByKol(kolId);
    List<String> history = items.listItemIdsByKol(kolId);
    if (!history.isEmpty()) {
      store.removeItemFields(history);
    }
    store.putAll(kol.kolCode(), fields(kol, links));
    store.putItemFields(itemFields(kol, links));
    return links.size();
  }

  /**
   * Full rebuild (task 06 §4.3, task 06b, task 11 §4.4): every KOL's key is replaced atomically
   * with exactly the fields re-read while holding that KOL's lock, KOLs without current mappings
   * lose their key, and {@code link:*} keys of KOLs missing from the database are deleted. The item
   * buckets cannot be replaced wholesale — they are shared by many KOLs — so the rebuild first
   * overwrites each KOL's current item fields, then sweeps all 100 buckets and deletes every field
   * that is neither part of the rebuild nor, re-checked against the database, a mapping committed
   * while the rebuild ran. Callers must hold the rebuild lock. A KOL whose lock cannot be acquired
   * fails the rebuild; the outbox worker retries its own rows.
   */
  public RebuildResult rebuildAll() {
    long started = System.currentTimeMillis();
    LOG.info("Link index rebuild started");
    int stale = store.deleteStagingKeys();
    Set<String> known = new HashSet<>();
    Set<String> validItemIds = new HashSet<>();
    int kolCount = 0;
    long fieldCount = 0;
    for (KolRefDTO kol : kols.listAll()) {
      known.add(kol.kolCode());
      var written = replaceUnderLock(kol, validItemIds);
      fieldCount += written.mappingFields;
      kolCount++;
    }
    int orphans = 0;
    for (String code : store.listIndexedKolCodes()) {
      if (!known.contains(code)) {
        store.delete(code);
        orphans++;
      }
    }
    long orphanItemFields = sweepItemBuckets(validItemIds);
    long elapsed = System.currentTimeMillis() - started;
    LOG.info(
        "Link index rebuild finished: {} KOLs, {} fields, {} item fields, {} orphan keys and {}"
            + " orphan item fields deleted, {} staging keys deleted, {} ms",
        kolCount,
        fieldCount,
        validItemIds.size(),
        orphans,
        orphanItemFields,
        stale,
        elapsed);
    return new RebuildResult(
        kolCount, fieldCount, orphans, validItemIds.size(), orphanItemFields, elapsed);
  }

  /**
   * Deletes item fields that no longer belong to a current mapping: for each bucket, every field
   * not written by this rebuild is a candidate, and each candidate is re-checked against the
   * database before deletion so a mapping committed while the rebuild ran survives (task 11 §4.4).
   */
  private long sweepItemBuckets(Set<String> validItemIds) {
    long deleted = 0;
    for (String bucket : LinkIndexStore.itemBuckets()) {
      List<String> candidates = new ArrayList<>();
      for (String field : store.listItemFields(bucket)) {
        if (!validItemIds.contains(field)) {
          candidates.add(field);
        }
      }
      if (candidates.isEmpty()) {
        continue;
      }
      candidates.removeAll(items.filterCurrentItemIds(candidates));
      if (!candidates.isEmpty()) {
        store.removeItemFields(candidates);
        deleted += candidates.size();
      }
    }
    return deleted;
  }

  /**
   * Re-reads the KOL and its current mappings inside {@link #kolLock}, then replaces the Redis key
   * and overwrites the KOL's current item fields. Returns the number of mapping fields written and
   * appends the item IDs to {@code validItemIds}.
   */
  private Written replaceUnderLock(KolRefDTO listed, Set<String> validItemIds) {
    AtomicInteger mappingFields = new AtomicInteger();
    try {
      boolean locked =
          locks.run(
              kolLock(listed.id()),
              KOL_LOCK_WAIT_SECONDS,
              -1,
              () -> {
                KolRefDTO kol = kol(listed.id());
                if (kol == null) {
                  store.delete(listed.kolCode());
                  return;
                }
                List<KolItemLinkDTO> links = items.listCurrentLinksByKol(kol.id());
                store.replace(kol.kolCode(), fields(kol, links));
                store.putItemFields(itemFields(kol, links));
                mappingFields.set(links.size());
                for (KolItemLinkDTO link : links) {
                  validItemIds.add(link.weidianItemId());
                }
              });
      if (!locked) {
        throw new IllegalStateException("timed out waiting for " + kolLock(listed.id()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while rebuilding " + listed.kolCode(), e);
    }
    return new Written(mappingFields.get());
  }

  private KolRefDTO kol(Long kolId) {
    return kols.listByIds(List.of(kolId)).stream().findFirst().orElse(null);
  }

  /** Drops every item field of the pair's history except the current mapping's item. */
  private void removeStaleItemFields(List<String> history, String currentItem) {
    removeStaleItemFields(history.stream().filter(itemId -> !itemId.equals(currentItem)).toList());
  }

  private void removeStaleItemFields(List<String> itemIds) {
    if (!itemIds.isEmpty()) {
      store.removeItemFields(itemIds);
    }
  }

  private static Map<String, String> fields(KolRefDTO kol, List<KolItemLinkDTO> links) {
    Map<String, String> fields = new LinkedHashMap<>(Math.max(16, links.size() * 2));
    for (KolItemLinkDTO link : links) {
      fields.put(link.productCode(), LinkRules.indexValue(kol, link));
    }
    return fields;
  }

  /** Item-index fields of the links: exclusive item ID to the same JSON value. */
  private static Map<String, String> itemFields(KolRefDTO kol, List<KolItemLinkDTO> links) {
    Map<String, String> fields = new LinkedHashMap<>(Math.max(16, links.size() * 2));
    for (KolItemLinkDTO link : links) {
      fields.put(link.weidianItemId(), LinkRules.indexValue(kol, link));
    }
    return fields;
  }

  private record Written(int mappingFields) {}

  /**
   * Outcome of a full rebuild. {@code fields} and {@code itemFields} count written hash fields of
   * {@code link:*} and {@code linkitem:*}; {@code orphanKeys} and {@code orphanItemFields} count
   * deleted leftovers.
   */
  public record RebuildResult(
      int kols,
      long fields,
      int orphanKeys,
      long itemFields,
      long orphanItemFields,
      long elapsedMillis) {}
}
