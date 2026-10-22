CREATE TABLE sys_user_departure_plan (
  id BIGINT NOT NULL CHECK (id > 0),
  tenant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  plan_type VARCHAR(16) NOT NULL,
  effective_at TIMESTAMPTZ(3) NOT NULL,
  heir_user_id BIGINT,
  initiator VARCHAR(16) NOT NULL,
  reason VARCHAR(255),
  status VARCHAR(16) NOT NULL,
  executed_at TIMESTAMPTZ(3),
  result VARCHAR(16),
  result_message VARCHAR(255),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_sys_user_departure_plan PRIMARY KEY (id),
  CONSTRAINT ck_sys_user_departure_plan_type CHECK (plan_type IN ('RESIGN', 'DISABLE')),
  CONSTRAINT ck_sys_user_departure_plan_initiator CHECK (initiator IN ('SELF', 'ADMIN')),
  CONSTRAINT ck_sys_user_departure_plan_status CHECK (status IN ('SCHEDULED', 'DONE', 'CANCELLED')),
  CONSTRAINT ck_sys_user_departure_plan_result
    CHECK (result IS NULL OR result IN ('TRANSFERRED', 'UNASSIGNED', 'HEIR_INVALID'))
);

COMMENT ON TABLE sys_user_departure_plan IS '商务离职 / 停用计划；每个账号最多一条未执行的计划';
COMMENT ON COLUMN sys_user_departure_plan.id IS '雪花算法主键';
COMMENT ON COLUMN sys_user_departure_plan.tenant_id IS '所属公司';
COMMENT ON COLUMN sys_user_departure_plan.user_id IS '被标记离职或停用的商务账号 ID';
COMMENT ON COLUMN sys_user_departure_plan.plan_type IS '计划类型：RESIGN 离职 / DISABLE 停用';
COMMENT ON COLUMN sys_user_departure_plan.effective_at IS '生效时间（UTC）；到期由定时任务执行';
COMMENT ON COLUMN sys_user_departure_plan.heir_user_id IS '继承商务 ID；空表示不指定继承人，达人生效后转为未分配';
COMMENT ON COLUMN sys_user_departure_plan.initiator IS '发起方式：SELF 本人 / ADMIN 管理员';
COMMENT ON COLUMN sys_user_departure_plan.reason IS '离职或停用原因（最多 255 字）';
COMMENT ON COLUMN sys_user_departure_plan.status IS '状态：SCHEDULED 待执行 / DONE 已执行 / CANCELLED 已撤销';
COMMENT ON COLUMN sys_user_departure_plan.executed_at IS '实际执行时间；未执行为空';
COMMENT ON COLUMN sys_user_departure_plan.result IS '执行结果：TRANSFERRED 达人已转交 / UNASSIGNED 达人转为未分配 / HEIR_INVALID 继承人不可用未转交';
COMMENT ON COLUMN sys_user_departure_plan.result_message IS '执行结果说明（最多 255 字）；未执行为空';
COMMENT ON COLUMN sys_user_departure_plan.create_by IS '创建人；本人提交时为自己';
COMMENT ON COLUMN sys_user_departure_plan.create_time IS '创建时间';
COMMENT ON COLUMN sys_user_departure_plan.update_by IS '最后修改人';
COMMENT ON COLUMN sys_user_departure_plan.update_time IS '最后修改时间';
COMMENT ON COLUMN sys_user_departure_plan.is_deleted IS '逻辑删除标记';

CREATE UNIQUE INDEX uk_sys_user_departure_plan_scheduled
  ON sys_user_departure_plan (tenant_id, user_id) WHERE status = 'SCHEDULED';
CREATE INDEX idx_sys_user_departure_plan_due
  ON sys_user_departure_plan (effective_at, id) WHERE status = 'SCHEDULED';
CREATE INDEX idx_sys_user_departure_plan_user
  ON sys_user_departure_plan (tenant_id, user_id, create_time DESC, id DESC);
