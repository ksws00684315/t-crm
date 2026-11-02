#!/bin/sh
# Runs once, when the PostgreSQL image initialises an EMPTY data directory (docker-entrypoint-initdb.d).
# POSTGRES_USER is the administration superuser; this script creates the application account that
# crm-admin uses (TCRM_DB_USERNAME) as a plain login role and makes it the owner of the database, so
# Flyway can create and alter the schema but the application cannot act as a superuser.
# Names and the password are read from the environment by psql itself (\getenv) and quoted by psql
# (:"x" quotes an identifier, :'x' a literal): never spliced into the SQL text, never on a command line.
set -eu

: "${TCRM_DB_USERNAME:?TCRM_DB_USERNAME must be set}"
: "${TCRM_DB_PASSWORD:?TCRM_DB_PASSWORD must be set}"

if [ "$TCRM_DB_USERNAME" = "$POSTGRES_USER" ]; then
  echo "TCRM_DB_USERNAME must differ from TCRM_DB_SUPERUSER_USERNAME" >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\getenv app_user TCRM_DB_USERNAME
\getenv app_password TCRM_DB_PASSWORD
\getenv app_db POSTGRES_DB
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_password';
ALTER DATABASE :"app_db" OWNER TO :"app_user";
SQL
