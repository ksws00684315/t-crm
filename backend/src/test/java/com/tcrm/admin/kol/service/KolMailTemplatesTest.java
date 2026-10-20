package com.tcrm.admin.kol.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcrm.admin.kol.service.KolMailTemplates.ResetLink;
import com.tcrm.admin.shared.MailMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class KolMailTemplatesTest {
  private final KolMailTemplates templates =
      new KolMailTemplates(new KolPortalUrls("https://kol.example.com"));

  @Test
  void shouldRenderBilingualInviteWithCompanyAndLink() {
    MailMessage mail = templates.invite("kol@example.com", "Acme & Co", "raw-token");
    assertThat(mail.to()).isEqualTo("kol@example.com");
    assertThat(mail.template()).isEqualTo("kol-invite");
    assertThat(mail.subject()).isEqualTo("[Acme & Co] Set up your creator account / 设置您的达人账号");
    // English first, Chinese after the separator; both name the company and carry the link.
    assertThat(mail.text()).startsWith("Hello,").contains("Acme & Co invites you");
    assertThat(mail.text().indexOf("Hello,")).isLessThan(mail.text().indexOf("您好，"));
    assertThat(mail.text())
        .contains("https://kol.example.com/activate?token=raw-token")
        .contains("72 小时内有效")
        .contains("邀请您加入其达人合作计划（SparkTrack）")
        .doesNotContain("T-CRM");
    assertThat(mail.html())
        .contains("https://kol.example.com/activate?token=raw-token")
        .contains("<strong>Acme &amp; Co</strong>");
  }

  @Test
  void shouldRenderOneLinkPerCompany_whenSeveralAccountsShareTheEmail() {
    MailMessage mail =
        templates.reset(
            "kol@example.com",
            List.of(new ResetLink("Alpha", "token-a"), new ResetLink("Beta", "token-b")));
    assertThat(mail.template()).isEqualTo("kol-reset");
    assertThat(mail.subject()).isEqualTo("[Alpha] Reset your password / 重置密码");
    assertThat(mail.text())
        .contains("Reset the account of Alpha")
        .contains("https://kol.example.com/reset?token=token-a")
        .contains("Reset the account of Beta")
        .contains("https://kol.example.com/reset?token=token-b")
        .contains("重置 Alpha 的账号密码")
        .contains("https://kol.example.com/reset?token=token-b")
        .contains("SparkTrack")
        .doesNotContain("T-CRM");
    assertThat(mail.text().indexOf("Hello,")).isLessThan(mail.text().indexOf("您好，"));
  }

  @Test
  void shouldEscapeHtmlInCompanyNames() {
    MailMessage mail = templates.reset("kol@example.com", List.of(new ResetLink("A<b>", "t")));
    assertThat(mail.html()).contains("<strong>A&lt;b&gt;</strong>");
  }

  @Test
  void shouldRefuseResetMailWithoutLinks() {
    assertThatThrownBy(() -> templates.reset("kol@example.com", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
