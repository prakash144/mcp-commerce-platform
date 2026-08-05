#!/bin/bash
set -e

# Creates multiple databases at Postgres init time.
# The COMPOSE file passes POSTGRES_MULTIPLE_DATABASES as a comma-separated list.

for db in $(echo "$POSTGRES_MULTIPLE_DATABASES" | tr ',' ' '); do
  echo "Creating database: $db"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE "$db";
EOSQL
done

echo "All databases created successfully."
