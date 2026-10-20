package com.tcrm.admin.kol.service;

import com.tcrm.admin.shared.MailMessage;
import com.tcrm.admin.shared.api.SystemConfigApi;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Invitation and password-reset mails (task 12a §4.5). Every mail carries English first and then
 * Chinese, names the company, and offers plain text plus a simple HTML rendering (design §2.4). The
 * links are the only secret material; they never reach logs or audit rows.
 */
@Component
public class KolMailTemplates {
  public static final String INVITE_TEMPLATE = "kol-invite";
  public static final String RESET_TEMPLATE = "kol-reset";

  private final KolPortalUrls links;
  private final SystemConfigApi configs;

  @Autowired
  public KolMailTemplates(KolPortalUrls links, SystemConfigApi configs) {
    this.links = links;
    this.configs = configs;
  }

  KolMailTemplates(KolPortalUrls links) {
    this(
        links,
        new SystemConfigApi() {
          @Override
          public String getValue(String key) {
            return "SparkTrack";
          }

          @Override
          public int getInt(String key) {
            return 72;
          }
        });
  }

  /** Invitation to a KOL created in the back office; the current configured TTL is included. */
  public MailMessage invite(String to, String company, String token) {
    String siteName = configs.getValue("site.name");
    int ttlHours = configs.getInt("kol.invite-ttl-hours");
    String url = links.activateUrl(token);
    String subject = "[" + company + "] Set up your creator account / 设置您的达人账号";
    String text =
        "Hello,\n\n"
            + company
            + " invites you to join its creator program on "
            + siteName
            + ".\n\n"
            + "Set up your account password (the link is valid for "
            + ttlHours
            + " hours and can be used once):\n"
            + url
            + "\n\n"
            + "If you were not expecting this email, you can ignore it.\n\n"
            + "----\n"
            + "您好，\n\n"
            + company
            + " 邀请您加入其达人合作计划（"
            + siteName
            + "）。\n\n"
            + "请设置您的账号密码（链接 "
            + ttlHours
            + " 小时内有效，仅可使用一次）：\n"
            + url
            + "\n\n"
            + "如果您没有预期收到这封邮件，请忽略它。\n";
    String html =
        "<p>Hello,</p><p><strong>%s</strong> invites you to join its creator program on %s.</p>"
                .formatted(escape(company), escape(siteName))
            + "<p>Set up your account password (the link is valid for "
            + ttlHours
            + " hours and can be used"
            + " once):</p>"
            + "<p><a href=\""
            + url
            + "\">Set up my account / 设置我的账号</a></p>"
            + "<p>If you were not expecting this email, you can ignore it.</p><hr>"
            + "<p>您好，</p><p><strong>%s</strong> 邀请您加入其达人合作计划（%s）。</p>"
                .formatted(escape(company), escape(siteName))
            + "<p>请设置您的账号密码（链接 "
            + ttlHours
            + " 小时内有效，仅可使用一次）：</p>"
            + "<p><a href=\""
            + url
            + "\">设置我的账号</a></p>"
            + "<p>如果您没有预期收到这封邮件，请忽略它。</p>";
    return new MailMessage(to, INVITE_TEMPLATE, subject, text, html);
  }

  /**
   * Password reset for every activated account of one email; with several companies each link only
   * resets that company's account, so one line per company is rendered.
   */
  public MailMessage reset(String to, List<ResetLink> resetLinks) {
    String siteName = configs.getValue("site.name");
    if (resetLinks.isEmpty()) {
      throw new IllegalArgumentException("a reset mail needs at least one link");
    }
    String company = resetLinks.getFirst().company();
    String subject = "[" + company + "] Reset your password / 重置密码";
    StringBuilder text =
        new StringBuilder(
            "Hello,\n\nWe received a request to reset your " + siteName + " password.\n\n");
    StringBuilder html =
        new StringBuilder(
            "<p>Hello,</p><p>We received a request to reset your "
                + escape(siteName)
                + " password.</p>");
    for (ResetLink link : resetLinks) {
      String url = links.resetUrl(link.token());
      text.append("Reset the account of ")
          .append(link.company())
          .append(" (the link is valid for 30 minutes and can be used once):\n")
          .append(url)
          .append("\n\n");
      html.append("<p>Reset the account of <strong>")
          .append(escape(link.company()))
          .append("</strong> (the link is valid for 30 minutes and can be used once):</p>")
          .append("<p><a href=\"")
          .append(url)
          .append("\">Reset password / 重置密码</a></p>");
    }
    text.append("If you did not request this, you can ignore this email.\n\n----\n您好，\n\n")
        .append("我们收到了重置 ")
        .append(siteName)
        .append(" 密码的请求。\n\n");
    html.append("<p>If you did not request this, you can ignore this email.</p><hr>")
        .append("<p>您好，</p><p>我们收到了重置 ")
        .append(escape(siteName))
        .append(" 密码的请求。</p>");
    for (ResetLink link : resetLinks) {
      String url = links.resetUrl(link.token());
      text.append("重置 ")
          .append(link.company())
          .append(" 的账号密码（链接 30 分钟内有效，仅可使用一次）：\n")
          .append(url)
          .append("\n\n");
      html.append("<p>重置 <strong>")
          .append(escape(link.company()))
          .append("</strong> 的账号密码（链接 30 分钟内有效，仅可使用一次）：</p>")
          .append("<p><a href=\"")
          .append(url)
          .append("\">重置密码</a></p>");
    }
    text.append("如果这不是您本人的操作，请忽略这封邮件。\n");
    html.append("<p>如果这不是您本人的操作，请忽略这封邮件。</p>");
    return new MailMessage(to, RESET_TEMPLATE, subject, text.toString(), html.toString());
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** One company's reset link; {@code token} is the raw one-time token. */
  public record ResetLink(String company, String token) {}
}
