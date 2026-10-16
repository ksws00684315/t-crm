CREATE TABLE crm_kol_bd_transfer (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    kol_id BIGINT NOT NULL,
    from_bd_user_id BIGINT,
    to_bd_user_id BIGINT,
    effective_at TIMESTAMPTZ(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    executed_at TIMESTAMPTZ(3),
    cancel_reason VARCHAR(255),
    create_by BIGINT,
    create_time TIMESTAMPTZ(3) NOT NULL,
    update_by BIGINT,
    update_time TIMESTAMPTZ(3) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_crm_kol_bd_transfer PRIMARY KEY (id),
    CONSTRAINT ck_crm_kol_bd_transfer_id CHECK (id > 0),
    CONSTRAINT ck_crm_kol_bd_transfer_status CHECK (status IN ('SCHEDULED', 'DONE', 'CANCELLED'))
);
COMMENT ON TABLE crm_kol_bd_transfer IS '达人商务转移记录（立即及预约）';
COMMENT ON COLUMN crm_kol_bd_transfer.id IS '雪花主键';
COMMENT ON COLUMN crm_kol_bd_transfer.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_kol_bd_transfer.kol_id IS '达人 ID';
COMMENT ON COLUMN crm_kol_bd_transfer.from_bd_user_id IS '来源商务 ID；空表示未分配';
COMMENT ON COLUMN crm_kol_bd_transfer.to_bd_user_id IS '目标商务 ID；空表示转为未分配、归公司';
COMMENT ON COLUMN crm_kol_bd_transfer.effective_at IS '约定生效时间（UTC）';
COMMENT ON COLUMN crm_kol_bd_transfer.status IS '状态：SCHEDULED 预约中 / DONE 已完成 / CANCELLED 已取消';
COMMENT ON COLUMN crm_kol_bd_transfer.executed_at IS '实际执行时间；未执行为空';
COMMENT ON COLUMN crm_kol_bd_transfer.cancel_reason IS '取消原因（最多 255 字）；非取消记录为空';
COMMENT ON COLUMN crm_kol_bd_transfer.create_by IS '创建人；系统操作为空';
COMMENT ON COLUMN crm_kol_bd_transfer.create_time IS '创建时间';
COMMENT ON COLUMN crm_kol_bd_transfer.update_by IS '修改人；系统操作为空';
COMMENT ON COLUMN crm_kol_bd_transfer.update_time IS '修改时间';
COMMENT ON COLUMN crm_kol_bd_transfer.is_deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX uk_crm_kol_bd_transfer_scheduled ON crm_kol_bd_transfer (kol_id) WHERE status = 'SCHEDULED';
CREATE INDEX idx_crm_kol_bd_transfer_due ON crm_kol_bd_transfer (effective_at, id) WHERE status = 'SCHEDULED';
CREATE INDEX idx_crm_kol_bd_transfer_from ON crm_kol_bd_transfer (tenant_id, from_bd_user_id, effective_at DESC, id DESC);
CREATE INDEX idx_crm_kol_bd_transfer_to ON crm_kol_bd_transfer (tenant_id, to_bd_user_id, effective_at DESC, id DESC);
CREATE INDEX idx_crm_kol_bd_transfer_kol ON crm_kol_bd_transfer (kol_id, effective_at DESC, id DESC);
