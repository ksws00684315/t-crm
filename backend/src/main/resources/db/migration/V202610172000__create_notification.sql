CREATE TABLE crm_notification (
    id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    params JSONB NOT NULL,
    biz_type VARCHAR(32),
    biz_id BIGINT,
    read_time TIMESTAMPTZ(3),
    create_time TIMESTAMPTZ(3) NOT NULL,
    CONSTRAINT pk_crm_notification PRIMARY KEY (id),
    CONSTRAINT ck_crm_notification_id CHECK (id > 0),
    CONSTRAINT ck_crm_notification_type CHECK (
        type IN (
            'PAYOUT_ACCOUNT_SUBMITTED',
            'ORDER_SYNC_FAILED',
            'BD_TRANSFER_CANCELLED',
            'KOLS_UNASSIGNED'))
);
COMMENT ON TABLE crm_notification IS '站内消息；只追加，不存标题和正文，由管理端按类型与参数渲染';
COMMENT ON COLUMN crm_notification.id IS '正值雪花主键';
COMMENT ON COLUMN crm_notification.tenant_id IS '所属公司';
COMMENT ON COLUMN crm_notification.user_id IS '接收人，sys_user.id';
COMMENT ON COLUMN crm_notification.type IS '消息类型：收款账户提交、订单同步失败、预约转移取消、达人转为未分配';
COMMENT ON COLUMN crm_notification.params IS '渲染参数（JSONB），只含编号、数量等非个人信息';
COMMENT ON COLUMN crm_notification.biz_type IS '关联业务类型；可空';
COMMENT ON COLUMN crm_notification.biz_id IS '关联业务主键；可空';
COMMENT ON COLUMN crm_notification.read_time IS '已读时间（UTC）；空表示未读';
COMMENT ON COLUMN crm_notification.create_time IS '创建时间（UTC）';
CREATE INDEX idx_crm_notification_inbox
    ON crm_notification (user_id, read_time, create_time DESC);
CREATE INDEX idx_crm_notification_tenant_time
    ON crm_notification (tenant_id, create_time);
