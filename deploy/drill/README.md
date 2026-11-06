# 恢复演练项目（季度一次）

RUNBOOK 第 17 章的演练用独立 Compose 项目。在生产主机上起一套与生产**互不相通**的
PostgreSQL / Redis / crm-admin / crm-redirect，用生产备份仓库恢复数据，验证备份可读、
恢复耗时和数据完整性。演练结束即删除，不留常驻容器。

## 为什么是独立项目

- 不碰生产数据卷：卷名 `tcrmdrill_pgdata` / `tcrmdrill_redisdata`，`down -v` 只删演练卷。
- 不碰生产网络：子网 `172.29.0.0/24`。**不能选与主机 VPC 重叠的网段**——AWS 默认 VPC 是
  `172.31.0.0/16`，用 `172.31.0.0/24` 会让 Docker 桥接占用 VPC 网关，容器全部断网，
  恢复报 S3 超时（2026-11-06 首次演练踩过）。
- **不写生产备份仓库**：`postgres` 以 `archive_mode=off` 启动。恢复出来的 `postgresql.conf`
  里带着生产的 `archive_command`，少了这一项，演练库会把 WAL 推进生产仓库。
- 不占用公网端口：管理端、中转只在 `127.0.0.1:18080` / `18081` 监听。

## 准备

```bash
mkdir -p /opt/tcrm-drill && chmod 700 /opt/tcrm-drill
cp /opt/tcrm/deploy/docker-compose.drill.yml /opt/tcrm-drill/   # 或在仓库目录直接用 -f 指向本文件
cp -p /opt/tcrm/deploy/.env /opt/tcrm-drill/.env                # 至少要有 PGBACKREST_*、密码与密钥
chmod 600 /opt/tcrm-drill/.env
```

`.env` 里有生产密钥，**不要提交**，也不要留在主机上超过演练本身。

## 执行

```bash
cd /opt/tcrm-drill
export TCRM_COMPOSE_FILE=/opt/tcrm-drill/docker-compose.drill.yml
export TCRM_ENV_FILE=/opt/tcrm-drill/.env

# 1. 恢复到最新归档位置
echo yes | /opt/tcrm/deploy/backup/restore-pitr.sh latest

# 2. 指定时间点（会覆盖上一步的结果，脚本加 --overwrite）
echo yes | /opt/tcrm/deploy/backup/restore-pitr.sh --overwrite "YYYY-MM-DD HH:MM:SS"

# 3. 逻辑备份解密恢复到一个独立库
PASS_FILE=$(grep '^TCRM_BACKUP_PASSPHRASE_FILE=' .env | cut -d= -f2-)
LATEST=$(ls -t /var/lib/tcrm/backups/tcrm-*.dump.gpg | head -1)
docker compose -f "$TCRM_COMPOSE_FILE" --env-file .env exec -T postgres \
  sh -c 'psql -U "$POSTGRES_USER" -d postgres -c "create database tcrm_logical"'
gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$PASS_FILE" --decrypt "$LATEST" \
  | docker compose -f "$TCRM_COMPOSE_FILE" --env-file .env exec -T postgres \
      sh -c 'pg_restore -U "$POSTGRES_USER" -d tcrm_logical --no-owner --no-privileges --exit-on-error'
```

## 核对

- 四条 SQL：`crm_kol`、`crm_order`（行数 + `max(create_time)`）、
  `crm_commission_wallet`（行数 + `sum(balance_cny)`）、`crm_commission_withdrawal`（行数 + `max(create_time)`），
  与生产对照；
- `flyway_schema_history` 行数与生产一致，crm-admin 日志出现
  `Successfully validated N migrations` 与 `Schema "public" is up to date`；
- `select pg_is_in_recovery()` 为 `f`；
- 记录恢复耗时（目标 ≤ 2 小时）与丢失窗口（目标 ≤ 约 5 分钟，`archive_timeout=300`）；
- S3 跨区复制、Object Lock 在 AWS 控制台确认（应用凭证没有读复制配置的权限）。

## 清理

```bash
docker compose -f "$TCRM_COMPOSE_FILE" --env-file .env down -v --remove-orphans
rm -rf /opt/tcrm-drill
docker ps -a --format '{{.Names}}' | grep -i drill   # 应无输出
docker volume ls --format '{{.Name}}' | grep -i drill
docker network ls --format '{{.Name}}' | grep -i drill
```

镜像与生产共用，不要删除。
