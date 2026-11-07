# Secrets the application needs. Terraform creates the containers only; the VALUES are set
# by a person (see README.md in this folder), so no secret value is ever in code or state.
resource "aws_secretsmanager_secret" "jwt" {
  #checkov:skip=CKV2_AWS_57:Rotated by hand (README runbook); the app reads one key at a time
  name        = "${var.project}/jwt-secret"
  description = "Base64 of >= 32 random bytes; signs access tokens (ATLAS_JWT_SECRET)"
  kms_key_id  = aws_kms_key.main.arn
}

resource "aws_secretsmanager_secret" "metrics_password" {
  #checkov:skip=CKV2_AWS_57:Rotated by hand (README runbook); the app reads one key at a time
  name        = "${var.project}/metrics-password"
  description = "Prometheus scrape password, >= 16 characters (ATLAS_METRICS_PASSWORD)"
  kms_key_id  = aws_kms_key.main.arn
}

resource "aws_secretsmanager_secret" "anchor_private_key" {
  #checkov:skip=CKV2_AWS_57:Rotated by hand (README runbook); the app reads one key at a time
  name        = "${var.project}/anchor-private-key"
  description = "Ed25519 PKCS#8 base64 (ATLAS_ANCHOR_PRIVATE_KEY); generate with AnchorKeys"
  kms_key_id  = aws_kms_key.main.arn
}

resource "aws_secretsmanager_secret" "anchor_public_keys" {
  #checkov:skip=CKV2_AWS_57:Rotated by hand (README runbook); the app reads one key at a time
  name        = "${var.project}/anchor-public-keys"
  description = "keyId=base64X509[,older keys] (ATLAS_ANCHOR_PUBLIC_KEYS)"
  kms_key_id  = aws_kms_key.main.arn
}
