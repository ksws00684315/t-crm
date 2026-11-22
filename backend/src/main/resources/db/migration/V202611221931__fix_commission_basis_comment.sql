-- Task 159 / D-01 定案（负责人 2026-11-22，任务 158）：计佣基数为订单实付按行分摊（任务 81 起的现行实现），
-- 原注释仍写「微店订单可提现金额」口径，与实现不一致；只改注释，不改数据与约束。

COMMENT ON COLUMN crm_commission_item_state.basis_cny IS '计佣金额基数（任务 81 / D-01 定案）：订单实付 pay_amount（含运费）按商品行实付占比分摊、尾差归实付最大行；未核实为空，不以 0 代替';
COMMENT ON COLUMN crm_commission_item_revision.basis_cny IS '本修订的计佣金额基数快照（订单实付按行分摊）；未核实为空';
