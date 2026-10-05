package com.tcrm.admin.kol.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.time.Instant;

/** One BD ownership period of a KOL; end time null means current. */
@TableName("crm_kol_bd_history")
public class KolBdHistoryDO extends BaseEntity {
  private Long kolId;
  private Long bdUserId;
  private Instant startTime;
  private Instant endTime;

  public Long getKolId() {
    return kolId;
  }

  public void setKolId(Long value) {
    this.kolId = value;
  }

  public Long getBdUserId() {
    return bdUserId;
  }

  public void setBdUserId(Long value) {
    this.bdUserId = value;
  }

  public Instant getStartTime() {
    return startTime;
  }

  public void setStartTime(Instant value) {
    this.startTime = value;
  }

  public Instant getEndTime() {
    return endTime;
  }

  public void setEndTime(Instant value) {
    this.endTime = value;
  }
}
