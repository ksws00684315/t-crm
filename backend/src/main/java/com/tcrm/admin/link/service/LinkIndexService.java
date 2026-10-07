package com.tcrm.admin.link.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import com.tcrm.admin.shared.DistributedLocks;
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
 * Maintains the Redis link index from current mappings (task 06 §4.3, task 06b). Incremental
 * refreshes run under {@link #kolLock} after the outbox worker re-reads the database. A field
 * exists for every current mapping; its status is 1 only when the KOL is enabled, the product on
 * sale and the shop enabled. Disabled links keep their field with status 0 so the redirect service
 * never mistakes them for unknown links.
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
   * field when a current mapping exists, deletes it otherwise. Returns the number of fields
   * written. Callers that share the index with a rebuild must hold {@link #kolLock}.
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
      return 0;
    }
    store.putAll(kol.kolCode(), fields(kol, List.of(link)));
    return 1;
  }

  /**
   * Rewrites every current field of the KOL after it, one of its products, or one of its shops was
   * enabled or disabled. Callers that share the index with a rebuild must hold {@link #kolLock}.
   */
  public int refreshKol(Long kolId) {
    KolRefDTO kol = kol(kolId);
    if (kol == null) {
      return 0;
    }
    List<KolItemLinkDTO> links = items.listCurrentLinksByKol(kolId);
    store.putAll(kol.kolCode(), fields(kol, links));
    return links.size();
  }

  /**
   * Full rebuild (task 06 §4.3, task 06b): every KOL's key is replaced atomically with exactly the
   * fields re-read while holding that KOL's lock, KOLs without current mappings lose their key, and
   * {@code link:*} keys of KOLs missing from the database are deleted. Callers must hold the
   * rebuild lock. A KOL whose lock cannot be acquired fails the rebuild; the outbox worker retries
   * its own rows.
   */
  public RebuildResult rebuildAll() {
    long started = System.currentTimeMillis();
    LOG.info("Link index rebuild started");
    int stale = store.deleteStagingKeys();
    Set<String> known = new HashSet<>();
    int kolCount = 0;
    long fieldCount = 0;
    for (KolRefDTO kol : kols.listAll()) {
      known.add(kol.kolCode());
      fieldCount += replaceUnderLock(kol);
      kolCount++;
    }
    int orphans = 0;
    for (String code : store.listIndexedKolCodes()) {
      if (!known.contains(code)) {
        store.delete(code);
        orphans++;
      }
    }
    long elapsed = System.currentTimeMillis() - started;
    LOG.info(
        "Link index rebuild finished: {} KOLs, {} fields, {} orphan keys and {} staging keys"
            + " deleted, {} ms",
        kolCount,
        fieldCount,
        orphans,
        stale,
        elapsed);
    return new RebuildResult(kolCount, fieldCount, orphans, elapsed);
  }

  /**
   * Re-reads the KOL and its current mappings inside {@link #kolLock}, then replaces the Redis key.
   * Returns the number of fields written.
   */
  private int replaceUnderLock(KolRefDTO listed) {
    AtomicInteger written = new AtomicInteger();
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
                written.set(links.size());
              });
      if (!locked) {
        throw new IllegalStateException("timed out waiting for " + kolLock(listed.id()));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while rebuilding " + listed.kolCode(), e);
    }
    return written.get();
  }

  private KolRefDTO kol(Long kolId) {
    return kols.listByIds(List.of(kolId)).stream().findFirst().orElse(null);
  }

  private static Map<String, String> fields(KolRefDTO kol, List<KolItemLinkDTO> links) {
    Map<String, String> fields = new LinkedHashMap<>(Math.max(16, links.size() * 2));
    for (KolItemLinkDTO link : links) {
      fields.put(link.productCode(), LinkRules.indexValue(kol.enabled(), link));
    }
    return fields;
  }

  /** Outcome of a full rebuild; {@code fields} counts written hash fields. */
  public record RebuildResult(int kols, long fields, int orphanKeys, long elapsedMillis) {}
}
