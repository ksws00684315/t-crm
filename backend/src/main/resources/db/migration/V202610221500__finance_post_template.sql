-- Finance post template (permission table 2026-10-22). Adds FINANCE next to OPERATOR and BD so a
-- finance account no longer has to borrow the operator template for display. The enum carries the
-- default codes and data scope, so no rows are inserted here. Constraint swaps only widen the
-- allowed values; existing rows are untouched.

ALTER TABLE sys_user_tenant DROP CONSTRAINT ck_sys_user_tenant_post_template;
ALTER TABLE sys_user_tenant ADD CONSTRAINT ck_sys_user_tenant_post_template
  CHECK (post_template IN ('OPERATOR', 'BD', 'FINANCE'));
COMMENT ON COLUMN sys_user_tenant.post_template IS '在该公司创建或加入时选择的岗位模板，仅用于显示：OPERATOR 运营、BD 商务、FINANCE 财务；空表示未选择';

ALTER TABLE sys_post_template DROP CONSTRAINT ck_sys_post_template_type;
ALTER TABLE sys_post_template ADD CONSTRAINT ck_sys_post_template_type
  CHECK (post_template IN ('BD', 'OPERATOR', 'FINANCE'));
COMMENT ON COLUMN sys_post_template.post_template IS '岗位模板：BD 商务 / OPERATOR 运营 / FINANCE 财务';
