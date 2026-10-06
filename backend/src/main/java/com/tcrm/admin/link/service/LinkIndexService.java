package com.tcrm.admin.link.service;

import com.tcrm.admin.kol.api.KolApi;
import com.tcrm.admin.kol.api.KolRefDTO;
import com.tcrm.admin.product.api.KolItemApi;
import com.tcrm.admin.product.api.KolItemLinkDTO;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maintains the Redis link index from current mappings (task 06 §4.3): incremental updates after
 * mapping, product, shop and KOL changes, and the per-KOL full rebuild. A field exists for every
 * current mapping; its status is 1 only when the KOL is enabled, the product on sale and the shop
 * enabled. Disabled links keep their field with status 0 so the redirect service never mistakes
 * them for unknown links.
 */
@Service
public class LinkIndexService {
  private static final Logger LOG = LoggerFactory.getLogger(LinkIndexService.class);
  private final KolItemApi items;
  private final KolApi kols;
  private final LinkIndexStore store;

  public LinkIndexService(KolItemApi items, KolApi kols, LinkIndexStore store) {
    this.items = items;
    this.kols = kols;
    this.store = store;
  }

  /**
   * Re-reads the current mapping of the pair after it was added, replaced or closed: writes the
   * field when a current mapping exists, deletes it otherwise. Returns the number of fields
   * written.
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

  /** Rewrites every current field of the KOL after it was enabled or disabled. */
  public int refreshKol(Long kolId) {
    KolRefDTO kol = kol(kolId);
    if (kol == null) {
      return 0;
    }
    List<KolItemLinkDTO> links = items.listCurrentLinksByKol(kolId);
    store.putAll(kol.kolCode(), fields(kol, links));
    return links.size();
  }

  /** Rewrites every current field of the product after it was put on or off sale. */
  public int refreshProduct(Long productId) {
    List<KolItemLinkDTO> links = items.listCurrentLinksByProduct(productId);
    Map<Long, List<KolItemLinkDTO>> byKol =
        links.stream().collect(Collectors.groupingBy(KolItemLinkDTO::kolId));
    int written = 0;
    for (KolRefDTO kol : kols.listByIds(byKol.keySet())) {
      List<KolItemLinkDTO> own = byKol.get(kol.id());
      store.putAll(kol.kolCode(), fields(kol, own));
      written += own.size();
    }
    return written;
  }

  /**
   * Rewrites every current field whose exclusive item belongs to the shop, one KOL at a time (a
   * shop may hold millions of mappings).
   */
  public int refreshShop(Long shopId) {
    int written = 0;
    for (KolRefDTO kol : kols.listAll()) {
      List<KolItemLinkDTO> links = items.listCurrentLinksByKolAndShop(kol.id(), shopId);
      store.putAll(kol.kolCode(), fields(kol, links));
      written += links.size();
    }
    return written;
  }

  /**
   * Full rebuild (task 06 §4.3): every KOL's key is replaced atomically with exactly its current
   * fields, KOLs without current mappings lose their key, and {@code link:*} keys of KOLs missing
   * from the database are deleted. Callers must hold the rebuild lock.
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
      List<KolItemLinkDTO> links = items.listCurrentLinksByKol(kol.id());
      store.replace(kol.kolCode(), fields(kol, links));
      kolCount++;
      fieldCount += links.size();
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
