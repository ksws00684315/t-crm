package com.tcrm.admin.kollibrary.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tcrm.admin.shared.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One creator of the creator library (crm_library_kol, task 143 §3.1). Every column is editable
 * through the profile form or the follow-up dialog, so the nullable optional ones use ALWAYS and a
 * cleared value is written as null. The calculated values of a creator (estimated commission, total
 * cost, ROI and totals) are not stored; {@code LibraryMetrics} derives them from the content rows.
 */
@TableName("crm_library_kol")
public class LibraryKolDO extends BaseEntity {
  /** Set on insert only; a row never changes its company. */
  @TableField(updateStrategy = FieldStrategy.NEVER)
  private Long tenantId;

  private LibraryPlatform platform;

  private String kolUsername;

  private String displayName;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long followers;

  private LibraryCountry countryCode;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String profileUrl;

  /** JSON array text; see crm_library_kol.categories. */
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String categories;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private Long avgViews;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private BigDecimal engagementRate;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String audienceCountry;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LibraryAudienceAge audienceAgeRange;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LibraryAudienceGender audienceGender;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String contact;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LibraryLevel contentQuality;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LibraryLevel salesPotential;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LibraryLevel cooperationAttitude;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LibraryGrade grade;

  /** JSON array text; see crm_library_kol.tags. */
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String tags;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String remark;

  private LibraryOutreachStatus outreachStatus;

  private String followUpOwner;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LocalDate lastContactDate;

  private LibrarySampleStatus sampleStatus;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String trackingNo;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private LocalDate sampleDate;

  @TableField(updateStrategy = FieldStrategy.ALWAYS)
  private String followUpRemark;

  public Long getTenantId() {
    return tenantId;
  }

  public void setTenantId(Long value) {
    this.tenantId = value;
  }

  public LibraryPlatform getPlatform() {
    return platform;
  }

  public void setPlatform(LibraryPlatform value) {
    this.platform = value;
  }

  public String getKolUsername() {
    return kolUsername;
  }

  public void setKolUsername(String value) {
    this.kolUsername = value;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String value) {
    this.displayName = value;
  }

  public Long getFollowers() {
    return followers;
  }

  public void setFollowers(Long value) {
    this.followers = value;
  }

  public LibraryCountry getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(LibraryCountry value) {
    this.countryCode = value;
  }

  public String getProfileUrl() {
    return profileUrl;
  }

  public void setProfileUrl(String value) {
    this.profileUrl = value;
  }

  public String getCategories() {
    return categories;
  }

  public void setCategories(String value) {
    this.categories = value;
  }

  public Long getAvgViews() {
    return avgViews;
  }

  public void setAvgViews(Long value) {
    this.avgViews = value;
  }

  public BigDecimal getEngagementRate() {
    return engagementRate;
  }

  public void setEngagementRate(BigDecimal value) {
    this.engagementRate = value;
  }

  public String getAudienceCountry() {
    return audienceCountry;
  }

  public void setAudienceCountry(String value) {
    this.audienceCountry = value;
  }

  public LibraryAudienceAge getAudienceAgeRange() {
    return audienceAgeRange;
  }

  public void setAudienceAgeRange(LibraryAudienceAge value) {
    this.audienceAgeRange = value;
  }

  public LibraryAudienceGender getAudienceGender() {
    return audienceGender;
  }

  public void setAudienceGender(LibraryAudienceGender value) {
    this.audienceGender = value;
  }

  public String getContact() {
    return contact;
  }

  public void setContact(String value) {
    this.contact = value;
  }

  public LibraryLevel getContentQuality() {
    return contentQuality;
  }

  public void setContentQuality(LibraryLevel value) {
    this.contentQuality = value;
  }

  public LibraryLevel getSalesPotential() {
    return salesPotential;
  }

  public void setSalesPotential(LibraryLevel value) {
    this.salesPotential = value;
  }

  public LibraryLevel getCooperationAttitude() {
    return cooperationAttitude;
  }

  public void setCooperationAttitude(LibraryLevel value) {
    this.cooperationAttitude = value;
  }

  public LibraryGrade getGrade() {
    return grade;
  }

  public void setGrade(LibraryGrade value) {
    this.grade = value;
  }

  public String getTags() {
    return tags;
  }

  public void setTags(String value) {
    this.tags = value;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String value) {
    this.remark = value;
  }

  public LibraryOutreachStatus getOutreachStatus() {
    return outreachStatus;
  }

  public void setOutreachStatus(LibraryOutreachStatus value) {
    this.outreachStatus = value;
  }

  public String getFollowUpOwner() {
    return followUpOwner;
  }

  public void setFollowUpOwner(String value) {
    this.followUpOwner = value;
  }

  public LocalDate getLastContactDate() {
    return lastContactDate;
  }

  public void setLastContactDate(LocalDate value) {
    this.lastContactDate = value;
  }

  public LibrarySampleStatus getSampleStatus() {
    return sampleStatus;
  }

  public void setSampleStatus(LibrarySampleStatus value) {
    this.sampleStatus = value;
  }

  public String getTrackingNo() {
    return trackingNo;
  }

  public void setTrackingNo(String value) {
    this.trackingNo = value;
  }

  public LocalDate getSampleDate() {
    return sampleDate;
  }

  public void setSampleDate(LocalDate value) {
    this.sampleDate = value;
  }

  public String getFollowUpRemark() {
    return followUpRemark;
  }

  public void setFollowUpRemark(String value) {
    this.followUpRemark = value;
  }
}
