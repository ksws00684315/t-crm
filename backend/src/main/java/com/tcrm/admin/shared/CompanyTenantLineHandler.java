package com.tcrm.admin.shared;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import java.util.Locale;
import java.util.Set;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;

/**
 * Company predicate for {@code TenantLineInnerInterceptor}. System scope ignores every table, so
 * inserts are not filled and callers must set {@code tenant_id}. {@code sys_tenant}, {@code
 * crm_link_index_outbox}, the platform-level {@code crm_fx_rate} (ADR-020) and {@code sys_user} are
 * always ignored. An account belongs to no company (ADR-022); company code reaches it only through
 * {@code sys_user_tenant}, which is filtered.
 */
@Component
public class CompanyTenantLineHandler implements TenantLineHandler {
  private static final Set<String> ALWAYS_IGNORED =
      Set.of("sys_tenant", "crm_link_index_outbox", "crm_fx_rate", "sys_user");
  private final CurrentTenantResolver login;

  public CompanyTenantLineHandler(CurrentTenantResolver login) {
    this.login = login;
  }

  @Override
  public Expression getTenantId() {
    if (TenantScope.inSystem()) {
      throw new MissingTenantException("system scope does not supply a tenant id");
    }
    Long company = TenantScope.companyId();
    if (company == null) {
      company = login.currentLoginTenantId();
    }
    if (company == null) {
      throw new MissingTenantException("no company scope and no logged-in company");
    }
    return new LongValue(company);
  }

  @Override
  public boolean ignoreTable(String tableName) {
    if (TenantScope.inSystem()) {
      return true;
    }
    return ALWAYS_IGNORED.contains(normalize(tableName));
  }

  /** Strips quotes and a schema qualifier, then lower-cases the table name. */
  static String normalize(String tableName) {
    if (tableName == null) {
      return "";
    }
    String name = tableName.replace("\"", "").trim();
    int dot = name.lastIndexOf('.');
    if (dot >= 0) {
      name = name.substring(dot + 1);
    }
    return name.toLowerCase(Locale.ROOT);
  }
}
