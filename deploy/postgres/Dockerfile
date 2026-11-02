# PostgreSQL 17 with pgBackRest (ADR-037 decision 5): full/differential backups and continuous WAL
# archiving to S3 with client-side encryption. Build context is deploy/postgres/ (docker-compose.prod.yml).
# pgBackRest comes from the PGDG apt repository that the official image already configures; the
# version is pinned. PGDG keeps old versions only for a limited time, so build the image once, push
# it to the registry (task 95) and deploy that image instead of rebuilding on the server.
FROM postgres:17.11

ARG PGBACKREST_VERSION=2.60.0-1.pgdg13+1

RUN apt-get update \
    && apt-get install -y --no-install-recommends "pgbackrest=${PGBACKREST_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /etc/pgbackrest /var/spool/pgbackrest /var/log/pgbackrest /var/lib/pgbackrest \
    && chown postgres:postgres /var/spool/pgbackrest /var/log/pgbackrest /var/lib/pgbackrest

# No secrets here: repository type, bucket, region, keys and the cipher passphrase come from the
# PGBACKREST_* environment variables (see deploy/.env.example).
COPY pgbackrest.conf /etc/pgbackrest/pgbackrest.conf
