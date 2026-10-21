#!/bin/sh
# Creates the DynamoDB tables used by the service against DynamoDB Local.
# Idempotent: existing tables are left untouched. In AWS the same schema is
# provisioned by infra/terraform/dynamodb.tf.
set -eu

ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:8000}"
REGION="${AWS_DEFAULT_REGION:-us-west-2}"
export AWS_PAGER=""

aws_ddb() {
  aws dynamodb "$@" --endpoint-url "$ENDPOINT" --region "$REGION"
}

wait_for_endpoint() {
  i=0
  until aws_ddb list-tables >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -gt 30 ]; then
      echo "DynamoDB at $ENDPOINT did not become ready" >&2
      exit 1
    fi
    sleep 1
  done
}

table_exists() {
  aws_ddb describe-table --table-name "$1" >/dev/null 2>&1
}

wait_for_endpoint

if table_exists short_urls; then
  echo "table short_urls already exists"
else
  echo "creating table short_urls"
  aws_ddb create-table \
    --table-name short_urls \
    --attribute-definitions \
        AttributeName=short_code,AttributeType=S \
        AttributeName=owner_id,AttributeType=S \
        AttributeName=created_at,AttributeType=N \
        AttributeName=url_hash,AttributeType=S \
    --key-schema AttributeName=short_code,KeyType=HASH \
    --global-secondary-indexes \
        'IndexName=owner-index,KeySchema=[{AttributeName=owner_id,KeyType=HASH},{AttributeName=created_at,KeyType=RANGE}],Projection={ProjectionType=ALL}' \
        'IndexName=url-hash-index,KeySchema=[{AttributeName=url_hash,KeyType=HASH}],Projection={ProjectionType=KEYS_ONLY}' \
    --billing-mode PAY_PER_REQUEST >/dev/null
  aws_ddb update-time-to-live \
    --table-name short_urls \
    --time-to-live-specification "Enabled=true,AttributeName=expires_at" >/dev/null
fi

echo "DynamoDB tables ready"
