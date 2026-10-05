package com.tcrm.admin.link.model;

import java.time.Instant;

/** One row of the append-only, monthly partitioned {@code crm_click_log} (task 06 §4.5). */
public class ClickLogDO {
  private Long id;
  private Instant clickTime;
  private Long kolId;
  private Long productId;
  private Long kolItemId;
  private String ipHash;
  private String ua;
  private String referer;
  private String country;
  private Boolean bot;
  private Boolean dup;
  private Instant createTime;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    this.id = value;
  }

  public Instant getClickTime() {
    return clickTime;
  }

  public void setClickTime(Instant value) {
    this.clickTime = value;
  }

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    this.kolId = value;
  }

  public Long getProductId() {
    return productId;
  }

  public void setProductId(Long value) {
    this.productId = value;
  }

  public Long getKolItemId() {
    return kolItemId;
  }

  public void setKolItemId(Long value) {
    this.kolItemId = value;
  }

  public String getIpHash() {
    return ipHash;
  }

  public void setIpHash(String value) {
    this.ipHash = value;
  }

  public String getUa() {
    return ua;
  }

  public void setUa(String value) {
    this.ua = value;
  }

  public String getReferer() {
    return referer;
  }

  public void setReferer(String value) {
    this.referer = value;
  }

  public String getCountry() {
    return country;
  }

  public void setCountry(String value) {
    this.country = value;
  }

  public Boolean getBot() {
    return bot;
  }

  public void setBot(Boolean value) {
    this.bot = value;
  }

  public Boolean getDup() {
    return dup;
  }

  public void setDup(Boolean value) {
    this.dup = value;
  }

  public Instant getCreateTime() {
    return createTime;
  }

  public void setCreateTime(Instant value) {
    this.createTime = value;
  }
}
