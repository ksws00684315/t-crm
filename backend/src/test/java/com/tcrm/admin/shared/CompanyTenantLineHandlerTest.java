package com.tcrm.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.Test;

class CompanyTenantLineHandlerTest {
  private final CompanyTenantLineHandler handler = new CompanyTenantLineHandler(() -> 8L);

  @Test
  void shouldPreferExplicitScope_thenLogin_andRejectWhenNeitherExists() {
    assertThat(handler.getTenantId()).isEqualTo(new LongValue(8));
    try (var scope = TenantScope.openCompany(3L)) {
      assertThat(handler.getTenantId()).isEqualTo(new LongValue(3));
      assertThat(handler.ignoreTable("crm_kol")).isFalse();
    }
    try (var scope = TenantScope.openSystem("no company")) {
      assertThat(handler.ignoreTable("crm_kol")).isTrue();
      assertThatThrownBy(handler::getTenantId).isInstanceOf(MissingTenantException.class);
    }
    var anonymous = new CompanyTenantLineHandler(() -> null);
    assertThatThrownBy(anonymous::getTenantId).isInstanceOf(MissingTenantException.class);
  }

  @Test
  void shouldIgnoreTenantTableAndOutbox_afterNormalizingTheName() {
    assertThat(CompanyTenantLineHandler.normalize("\"Public\".Sys_Tenant")).isEqualTo("sys_tenant");
    assertThat(handler.ignoreTable("public.sys_tenant")).isTrue();
    assertThat(handler.ignoreTable("\"crm_link_index_outbox\"")).isTrue();
    assertThat(handler.ignoreTable("crm_kol")).isFalse();
    assertThat(handler.ignoreTable(null)).isFalse();
  }

  @Test
  void shouldRewriteCompanySql_andLeaveSystemScopeAndIgnoredTablesAlone() {
    var rewriter = new Rewriter(handler);
    assertThat(rewriter.rewrite("SELECT nextval('crm_kol_code_seq')"))
        .isEqualTo("SELECT nextval('crm_kol_code_seq')");
    assertThat(rewriter.rewrite("SELECT EXISTS (SELECT 1 FROM crm_kol WHERE kol_code = ?)"))
        .contains("tenant_id = 8");
    assertThat(
            rewriter.rewrite(
                "SELECT id FROM crm_kol WHERE id = ? AND is_deleted = FALSE FOR UPDATE"))
        .contains("tenant_id = 8")
        .containsIgnoringCase("FOR UPDATE");
    assertThat(rewriter.rewrite("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))"))
        .doesNotContain("tenant_id");
    assertThat(
            rewriter.rewrite(
                "UPDATE crm_kol_bd_history SET end_time = ? WHERE kol_id = ? AND end_time IS NULL"))
        .contains("tenant_id = 8")
        .contains("SET end_time = ?");
    String insert =
        "INSERT INTO crm_click_log (id, click_time) VALUES (?, ?) ON CONFLICT (id, click_time) DO NOTHING";
    assertThat(rewriter.rewrite(insert)).contains("tenant_id").contains("ON CONFLICT");
    String listed =
        "INSERT INTO crm_click_log (id, click_time, tenant_id) VALUES (?, ?, ?)"
            + " ON CONFLICT (id, click_time) DO NOTHING";
    String rewrittenListed = rewriter.rewrite(listed);
    assertThat(rewriter.rewrite(listed)).contains("tenant_id");
    assertThat(rewrittenListed).doesNotContain("tenant_id, tenant_id");
    assertThat(rewriter.rewrite("SELECT id FROM sys_tenant WHERE id = ?"))
        .doesNotContain("tenant_id =");
    assertThat(rewriter.rewrite("SELECT id FROM crm_link_index_outbox WHERE id = ?"))
        .doesNotContain("tenant_id");
    String join =
        "SELECT ki.id FROM crm_kol_item ki"
            + " JOIN crm_product p ON p.id = ki.product_id"
            + " JOIN crm_shop s ON s.id = ki.shop_id WHERE ki.kol_id = ?";
    String rewritten = rewriter.rewrite(join);
    assertThat(rewritten).contains("p.tenant_id = 8").contains("s.tenant_id = 8");
    assertThat(rewritten).contains("ki.tenant_id = 8");
    try (var scope = TenantScope.openSystem("rewrite probe")) {
      assertThat(rewriter.rewrite("SELECT id FROM crm_kol WHERE id = ?"))
          .doesNotContain("tenant_id");
    }
  }

  /** Exposes {@code parserMulti} and a parse that does not apply the tenant handler. */
  private static final class Rewriter extends TenantLineInnerInterceptor {
    private Rewriter(CompanyTenantLineHandler handler) {
      super(handler);
    }

    private String rewrite(String sql) {
      return parserMulti(sql, null);
    }
  }
}
