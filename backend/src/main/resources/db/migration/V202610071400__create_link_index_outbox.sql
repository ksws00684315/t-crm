-- Task 06b: transactional outbox for the Redis link index (ADR-013).
-- Inserted in the business transaction, deleted after the background sync applies it.
-- Retry columns (attempts, next_attempt_at, last_error) are updated in place on failure;
-- there is no update_by / update_time / is_deleted (append-and-delete, plus those retries).

CREATE TABLE crm_link_index_outbox (
  id BIGINT NOT NULL,
  scope VARCHAR(16) NOT NULL,
  scope_id BIGINT NOT NULL,
  product_id BIGINT,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ(3) NOT NULL,
  last_error VARCHAR(500),
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_link_index_outbox PRIMARY KEY (id),
  CONSTRAINT ck_crm_link_index_outbox_id CHECK (id > 0),
  CONSTRAINT ck_crm_link_index_outbox_scope CHECK (scope IN ('KOL', 'PRODUCT', 'SHOP', 'MAPPING')),
  CONSTRAINT ck_crm_link_index_outbox_product CHECK (
    (scope = 'MAPPING' AND product_id IS NOT NULL)
    OR (scope <> 'MAPPING' AND product_id IS NULL)
  ),
  CONSTRAINT ck_crm_link_index_outbox_attempts CHECK (attempts >= 0)
);

-- Due rows are claimed in (next_attempt_at, id) order, at most 200 per fetch.
CREATE INDEX idx_crm_link_index_outbox_due
  ON crm_link_index_outbox (next_attempt_at, id);

-- Oldest unprocessed row for the wait-time alarm (MIN(create_time)).
CREATE INDEX idx_crm_link_index_outbox_create_time
  ON crm_link_index_outbox (create_time);

-- Rows that have already failed several times (attempts >= 5 alarm).
CREATE INDEX idx_crm_link_index_outbox_attempts
  ON crm_link_index_outbox (id)
  WHERE attempts >= 5;

COMMENT ON TABLE crm_link_index_outbox IS '链接索引同步登记；与业务变更同一事务插入，同步成功后删除。失败时只更新 attempts、next_attempt_at、last_error，不丢弃。省略修改类字段与逻辑删除';
COMMENT ON COLUMN crm_link_index_outbox.id IS '正数雪花主键';
COMMENT ON COLUMN crm_link_index_outbox.scope IS '变更范围：KOL 达人、PRODUCT 原商品、SHOP 店铺、MAPPING 一条对应关系';
COMMENT ON COLUMN crm_link_index_outbox.scope_id IS '范围主键：KOL/MAPPING 为达人 id，PRODUCT 为原商品 id，SHOP 为店铺 id';
COMMENT ON COLUMN crm_link_index_outbox.product_id IS '原商品 id；仅 MAPPING 有值，其余为空';
COMMENT ON COLUMN crm_link_index_outbox.attempts IS '已失败次数，从 0 起；成功后行被删除';
COMMENT ON COLUMN crm_link_index_outbox.next_attempt_at IS '下次可处理时间（UTC）；新行等于创建时间';
COMMENT ON COLUMN crm_link_index_outbox.last_error IS '最近一次失败摘要（英文，最多 500 字符）；尚未失败时为空';
COMMENT ON COLUMN crm_link_index_outbox.create_time IS '登记时间（UTC）；等待时间从这里起算';
