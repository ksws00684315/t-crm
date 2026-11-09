package com.tcrm.admin.kollibrary.model;

/**
 * Content category (requirement 1.2.6 ①: multi-select, one creator may span several categories).
 */
public enum LibraryCategory implements LibraryOption {
  BEAUTY_PERSONAL_CARE("美妆个护", "Beauty & personal care"),
  HEALTH_SUPPLEMENTS("保健营养", "Health & supplements"),
  HOME_DAILY("家居日用", "Home & daily use"),
  ELECTRONICS_3C("3C 数码", "3C & electronics"),
  FASHION_ACCESSORIES("服饰配饰", "Fashion & accessories"),
  MATERNAL_BABY("母婴", "Mother & baby"),
  FOOD_BEVERAGE("食品饮料", "Food & beverage"),
  PET("宠物", "Pets"),
  SPORTS_OUTDOOR("运动户外", "Sports & outdoors"),
  OTHER("其他", "Other");

  private final String nameZh;
  private final String nameEn;

  LibraryCategory(String nameZh, String nameEn) {
    this.nameZh = nameZh;
    this.nameEn = nameEn;
  }

  @Override
  public String nameZh() {
    return nameZh;
  }

  @Override
  public String nameEn() {
    return nameEn;
  }
}
