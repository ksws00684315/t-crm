CREATE TABLE crm_task (
  id BIGINT NOT NULL,
  task_type VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  total INTEGER NOT NULL DEFAULT 0,
  done INTEGER NOT NULL DEFAULT 0,
  failed INTEGER NOT NULL DEFAULT 0,
  message VARCHAR(500),
  started_at TIMESTAMPTZ(3),
  finished_at TIMESTAMPTZ(3),
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  update_by BIGINT,
  update_time TIMESTAMPTZ(3) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_crm_task PRIMARY KEY (id),
  CONSTRAINT ck_crm_task_id CHECK (id > 0),
  CONSTRAINT ck_crm_task_type CHECK (task_type IN ('KOL_IMPORT')),
  CONSTRAINT ck_crm_task_status CHECK (status IN ('WAITING', 'RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED')),
  CONSTRAINT ck_crm_task_counts CHECK (total >= 0 AND done >= 0 AND failed >= 0 AND failed <= done)
);
-- Startup cleanup of unfinished tasks; finished tasks are not indexed.
CREATE INDEX idx_crm_task_status ON crm_task (status) WHERE status IN ('WAITING', 'RUNNING');
COMMENT ON TABLE crm_task IS '批量任务；雪花 ID，逻辑删除';
COMMENT ON COLUMN crm_task.id IS '正数雪花主键';
COMMENT ON COLUMN crm_task.task_type IS '任务类型：KOL_IMPORT 达人导入（新增类型时通过迁移扩充约束）';
COMMENT ON COLUMN crm_task.status IS '状态：WAITING 排队中、RUNNING 执行中、SUCCESS 全部成功、PARTIAL 部分失败、FAILED 全部失败或无法执行';
COMMENT ON COLUMN crm_task.total IS '待处理数据行数';
COMMENT ON COLUMN crm_task.done IS '已处理行数（含失败行）';
COMMENT ON COLUMN crm_task.failed IS '失败行数';
COMMENT ON COLUMN crm_task.message IS '任务级失败原因（英文）；空表示无';
COMMENT ON COLUMN crm_task.started_at IS '开始执行时间（UTC）；空表示尚未开始';
COMMENT ON COLUMN crm_task.finished_at IS '结束时间（UTC）；空表示未结束';
COMMENT ON COLUMN crm_task.create_by IS '提交人 ID；系统任务为空';
COMMENT ON COLUMN crm_task.create_time IS '创建时间（UTC）';
COMMENT ON COLUMN crm_task.update_by IS '修改人 ID；系统操作为空';
COMMENT ON COLUMN crm_task.update_time IS '修改时间（UTC）';
COMMENT ON COLUMN crm_task.is_deleted IS '逻辑删除：false 有效、true 已删除';

CREATE TABLE crm_task_file (
  id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  kind VARCHAR(8) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(128) NOT NULL,
  content BYTEA NOT NULL,
  create_by BIGINT,
  create_time TIMESTAMPTZ(3) NOT NULL,
  CONSTRAINT pk_crm_task_file PRIMARY KEY (id),
  CONSTRAINT ck_crm_task_file_id CHECK (id > 0),
  CONSTRAINT ck_crm_task_file_kind CHECK (kind IN ('INPUT', 'ERROR'))
);
-- One file of each kind per task; also serves lookups by task.
CREATE UNIQUE INDEX uk_crm_task_file_task_kind ON crm_task_file (task_id, kind);
COMMENT ON TABLE crm_task_file IS '批量任务文件（上传原文件与错误文件），内容存数据库；只追加表，省略修改字段与逻辑删除';
COMMENT ON COLUMN crm_task_file.id IS '正数雪花主键';
COMMENT ON COLUMN crm_task_file.task_id IS '任务 crm_task.id';
COMMENT ON COLUMN crm_task_file.kind IS '文件类型：INPUT 上传原文件、ERROR 错误文件';
COMMENT ON COLUMN crm_task_file.file_name IS '文件名（下载时使用）';
COMMENT ON COLUMN crm_task_file.content_type IS 'MIME 类型';
COMMENT ON COLUMN crm_task_file.content IS '文件内容';
COMMENT ON COLUMN crm_task_file.create_by IS '创建人 ID；系统操作为空';
COMMENT ON COLUMN crm_task_file.create_time IS '创建时间（UTC）';
