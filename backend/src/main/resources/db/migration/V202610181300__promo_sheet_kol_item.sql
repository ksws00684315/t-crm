-- Task 26 (ADR-024): promo-sheet uploads create mappings that may have no original product.

-- The original product becomes nullable: a sheet-upload mapping only carries the copied Weidian
-- item ID until a product is found by shop + item ID (never guessed). Existing rows keep values.
ALTER TABLE crm_kol_item ALTER COLUMN product_id DROP NOT NULL;

-- Name snapshot from the promo sheet (NAME / 名称 column of the same row); only shown in the
-- KOL portal when no original product name exists.
ALTER TABLE crm_kol_item ADD COLUMN item_name VARCHAR(255);

-- Where the mapping came from. IMPORT is the Excel import of task 05 (previously the only
-- source); SHEET_UPLOAD is this task; COPY is reserved for the script copy flow (task 17b).
ALTER TABLE crm_kol_item ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'IMPORT';

ALTER TABLE crm_kol_item
  ADD CONSTRAINT ck_crm_kol_item_source CHECK (source IN ('IMPORT', 'SHEET_UPLOAD', 'COPY'));

COMMENT ON COLUMN crm_kol_item.product_id IS '原商品 crm_product.id；空表示尚无原商品（推广表上传，ADR-024）';
COMMENT ON COLUMN crm_kol_item.item_name IS '商品名称快照（推广表 NAME 列）；无原商品名称时展示';
COMMENT ON COLUMN crm_kol_item.source IS '来源：IMPORT 导入 / SHEET_UPLOAD 上传推广表 / COPY 脚本复制（预留）';
