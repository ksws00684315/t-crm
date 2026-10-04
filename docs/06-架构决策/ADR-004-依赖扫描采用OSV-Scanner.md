# ADR-004 依赖扫描采用 OSV-Scanner

- 状态：已接受（2026-10-04）
- 决策人：项目负责人

## 背景

规范要求 CI 扫描依赖漏洞与许可证。任务 02 原计划使用 GitHub 官方的 `actions/dependency-review-action`，执行时 CI 报错"该仓库不支持依赖审查"：该功能对私有仓库需要 GitHub Advanced Security 授权，本项目为个人账号下的私有仓库。

## 决定

1. 使用 Google 维护的 **OSV-Scanner** 扫描全仓库依赖（Maven 与 pnpm），同时检查漏洞与许可证白名单；发现即阻断，豁免登记在 `osv-scanner.toml` 并写明原因与到期日。
2. 前端另运行 `pnpm audit`；开启仓库 Dependabot 漏洞告警（私有仓库免费）。
3. 不上传 SARIF 到 GitHub 代码扫描（同样需要 Advanced Security）。
4. 所有第三方 Action 固定到完整提交 SHA，不再只写版本标签。

## 备选方案及放弃原因

| 方案 | 放弃原因 |
|---|---|
| GitHub 依赖审查 | 私有仓库需购买 Advanced Security |
| Trivy | 2026 年 3 月 `aquasecurity/trivy-action` 的 77 个版本标签中 76 个被强行改指向恶意提交，用于窃取 CI 密钥（[Aqua 官方说明](https://www.aquasec.com/blog/trivy-supply-chain-attack-what-you-need-to-know)）；风险已处置，但本项目选择更稳妥的方案 |
| OWASP Dependency-Check | 需要申请并维护 NVD API 密钥，扫描耗时长；不覆盖许可证检查 |

## 影响

- 扫描对任何已知漏洞都阻断（不按严重级别过滤），初期可能需要处理现有依赖的低危漏洞或登记豁免。
- 若将来仓库获得 Advanced Security 授权，可增加依赖审查与代码扫描，不替换 OSV-Scanner。
