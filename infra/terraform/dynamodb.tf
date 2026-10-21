# System of record. Schema mirrors scripts/create-tables.sh (DynamoDB Local).
resource "aws_dynamodb_table" "short_urls" {
  name         = "short_urls"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "short_code"

  attribute {
    name = "short_code"
    type = "S"
  }
  attribute {
    name = "owner_id"
    type = "S"
  }
  attribute {
    name = "created_at"
    type = "N"
  }
  attribute {
    name = "url_hash"
    type = "S"
  }

  global_secondary_index {
    name            = "owner-index"
    hash_key        = "owner_id"
    range_key       = "created_at"
    projection_type = "ALL"
  }

  global_secondary_index {
    name            = "url-hash-index"
    hash_key        = "url_hash"
    projection_type = "KEYS_ONLY"
  }

  ttl {
    attribute_name = "expires_at"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  deletion_protection_enabled = var.environment == "prod"

  tags = { Name = "short_urls" }
}
