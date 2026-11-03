-- Commission-owned audit of authorized missing commission rates; append-only.
CREATE TABLE crm_commission_rate_supplement (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    kol_id BIGINT NOT NULL,
    rate NUMERIC(6,4) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    create_by BIGINT NOT NULL,
    create_time TIMESTAMPTZ(3) NOT NULL,
    CONSTRAINT pk_crm_commission_rate_supplement PRIMARY KEY (id),
    CONSTRAINT ck_crm_commission_rate_supplement_id CHECK (id > 0),
    CONSTRAINT ck_crm_commission_rate_supplement_rate CHECK (rate >= 0 AND rate <= 1),
    CONSTRAINT ck_crm_commission_rate_supplement_reason CHECK (length(trim(reason)) > 0),
    CONSTRAINT uk_crm_commission_rate_supplement_line UNIQUE (tenant_id, order_item_id, kol_id)
);
COMMENT ON TABLE crm_commission_rate_supplement IS 'Append-only authorized missing commission rate audit; no update or logical deletion fields';
COMMENT ON COLUMN crm_commission_rate_supplement.id IS 'Snowflake identifier';
COMMENT ON COLUMN crm_commission_rate_supplement.tenant_id IS 'Owning company';
COMMENT ON COLUMN crm_commission_rate_supplement.order_id IS 'Target CRM order';
COMMENT ON COLUMN crm_commission_rate_supplement.order_item_id IS 'Explicit target order line';
COMMENT ON COLUMN crm_commission_rate_supplement.kol_id IS 'Attributed creator at supplementation; never applied to another creator';
COMMENT ON COLUMN crm_commission_rate_supplement.rate IS 'Authorized rate fraction from zero through one';
COMMENT ON COLUMN crm_commission_rate_supplement.reason IS 'Required operator explanation';
COMMENT ON COLUMN crm_commission_rate_supplement.create_by IS 'Company super administrator who authorized the rate';
COMMENT ON COLUMN crm_commission_rate_supplement.create_time IS 'UTC authorization time';
