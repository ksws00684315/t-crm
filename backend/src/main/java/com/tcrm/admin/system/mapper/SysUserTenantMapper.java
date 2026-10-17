package com.tcrm.admin.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcrm.admin.security.api.Role;
import com.tcrm.admin.system.model.CompanyCountRow;
import com.tcrm.admin.system.model.MemberRow;
import com.tcrm.admin.system.model.MembershipRow;
import com.tcrm.admin.system.model.SysUserTenantDO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Memberships. Every company query names its company explicitly; inside a company scope the tenant
 * line adds the same predicate on {@code sys_user_tenant}. {@code sys_user} is always ignored by
 * the tenant line, so the join never widens the result beyond the membership's company.
 */
@Mapper
public interface SysUserTenantMapper extends BaseMapper<SysUserTenantDO> {
  String MEMBER_COLUMNS =
      """
      SELECT m.user_id, m.tenant_id, m.role, m.post_template, m.data_scope, m.status,
             m.last_login_time, m.create_time, u.username, u.nickname, u.email,
             u.status AS account_status
      FROM sys_user_tenant m
      JOIN sys_user u ON u.id = m.user_id AND u.is_deleted = FALSE
      """;

  /**
   * Members of one company, newest first. {@code pattern} is an ILIKE pattern with wildcards
   * already escaped, or null for every member.
   */
  @Select(
      "<script>"
          + MEMBER_COLUMNS
          + " WHERE m.tenant_id = #{tenantId} AND m.is_deleted = FALSE"
          + "<if test='pattern != null'> AND u.username ILIKE #{pattern}</if>"
          + "<if test='role != null'> AND m.role = #{role}</if>"
          + "<if test='status != null'> AND m.status = #{status}</if>"
          + " ORDER BY m.create_time DESC, m.user_id DESC"
          + "</script>")
  IPage<MemberRow> pageMembers(
      Page<MemberRow> page,
      @Param("tenantId") Long tenantId,
      @Param("pattern") String pattern,
      @Param("role") Role role,
      @Param("status") Integer status);

  /** The live membership of this account in this company joined with the account, or null. */
  @Select(
      MEMBER_COLUMNS
          + " WHERE m.user_id = #{userId} AND m.tenant_id = #{tenantId} AND m.is_deleted = FALSE")
  MemberRow getMember(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

  /** Live members of this company among {@code userIds}; unknown ids are skipped. */
  @Select(
      "<script>"
          + MEMBER_COLUMNS
          + " WHERE m.tenant_id = #{tenantId} AND m.is_deleted = FALSE AND m.user_id IN"
          + "<foreach collection='userIds' item='id' open='(' separator=',' close=')'>#{id}"
          + "</foreach>"
          + "</script>")
  List<MemberRow> listMembers(
      @Param("tenantId") Long tenantId, @Param("userIds") Collection<Long> userIds);

  /** Live members of this company with this role, oldest first. */
  @Select(
      MEMBER_COLUMNS
          + " WHERE m.tenant_id = #{tenantId} AND m.role = #{role} AND m.is_deleted = FALSE"
          + " ORDER BY m.create_time, m.user_id")
  List<MemberRow> listByRole(@Param("tenantId") Long tenantId, @Param("role") Role role);

  /**
   * Account ids of the enabled super administrators of this company, ordered by id: the membership
   * and the account are both enabled. Other companies are never read.
   */
  @Select(
      "SELECT m.user_id FROM sys_user_tenant m"
          + " JOIN sys_user u ON u.id = m.user_id AND u.is_deleted = FALSE"
          + " WHERE m.tenant_id = #{tenantId} AND m.role = 'SUPER_ADMIN' AND m.status = 1"
          + " AND m.is_deleted = FALSE AND u.status = 1 ORDER BY m.user_id")
  List<Long> listEnabledSuperAdminIds(@Param("tenantId") Long tenantId);

  /**
   * Usable BDs of this company ordered by username: enabled account and membership, STAFF with OWN
   * scope, holding {@code kolEditCode} in this company.
   */
  @Select(
      MEMBER_COLUMNS
          + """
           WHERE m.tenant_id = #{tenantId} AND m.is_deleted = FALSE AND m.status = 1
             AND u.status = 1 AND m.role = 'STAFF' AND m.data_scope = 'OWN'
             AND EXISTS (
               SELECT 1 FROM sys_user_permission p
               WHERE p.user_id = m.user_id AND p.tenant_id = m.tenant_id
                 AND p.permission_code = #{kolEditCode})
           ORDER BY u.username, m.user_id
          """)
  List<MemberRow> listUsableBds(
      @Param("tenantId") Long tenantId, @Param("kolEditCode") String kolEditCode);

  /**
   * Locks this account's live membership of this company until the surrounding transaction ends;
   * null when there is none.
   */
  @Select(
      "SELECT id FROM sys_user_tenant WHERE user_id = #{userId} AND tenant_id = #{tenantId}"
          + " AND is_deleted = FALSE FOR UPDATE")
  Long lockMember(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

  /**
   * Locks the enabled super administrators of this company until the surrounding transaction ends
   * and returns their account ids; concurrent demotions or disables in this company are serialized
   * by this lock. Other companies are not counted.
   */
  @Select(
      "SELECT user_id FROM sys_user_tenant WHERE tenant_id = #{tenantId} AND role = 'SUPER_ADMIN'"
          + " AND status = 1 AND is_deleted = FALSE ORDER BY user_id FOR UPDATE")
  List<Long> lockEnabledSuperAdmins(@Param("tenantId") Long tenantId);

  /**
   * Every live membership of this account with its company, ordered by company name. Crosses
   * companies: callers run in system scope.
   */
  @Select(
      """
      SELECT m.tenant_id, t.name AS tenant_name, t.status AS tenant_status, m.role, m.status
      FROM sys_user_tenant m
      JOIN sys_tenant t ON t.id = m.tenant_id AND t.is_deleted = FALSE
      WHERE m.user_id = #{userId} AND m.is_deleted = FALSE
      ORDER BY t.name, t.id
      """)
  List<MembershipRow> listMemberships(@Param("userId") Long userId);

  /**
   * Live membership counts across every company for these accounts. Crosses companies (counts only,
   * no company data): callers run in system scope.
   */
  @Select(
      "<script>SELECT user_id, COUNT(*) AS company_count FROM sys_user_tenant"
          + " WHERE is_deleted = FALSE AND user_id IN"
          + "<foreach collection='userIds' item='id' open='(' separator=',' close=')'>#{id}"
          + "</foreach> GROUP BY user_id</script>")
  List<CompanyCountRow> countCompanies(@Param("userIds") Collection<Long> userIds);
}
