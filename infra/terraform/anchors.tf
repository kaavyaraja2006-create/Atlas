# Audit anchor log (docs/security/hardening.md §4). The threat is a DATABASE superuser
# rewriting history; the anchor log therefore lives outside the database, on EFS, which
# RDS credentials cannot reach. AWS Backup copies it daily into a vault whose lock stops
# anyone (including this account's admins, once the lock is past its grace period)
# from deleting a copy before its retention ends.
resource "aws_efs_file_system" "anchors" {
  encrypted  = true
  kms_key_id = aws_kms_key.main.arn
  tags       = { Name = "${var.project}-anchors" }

  lifecycle_policy {
    transition_to_ia = "AFTER_30_DAYS"
  }
}

resource "aws_efs_mount_target" "anchors" {
  count           = 2
  file_system_id  = aws_efs_file_system.anchors.id
  subnet_id       = aws_subnet.public[count.index].id
  security_groups = [aws_security_group.efs.id]
}

# The backend runs as uid 10001 (backend/Dockerfile); it sees only /anchors.
resource "aws_efs_access_point" "anchors" {
  file_system_id = aws_efs_file_system.anchors.id
  posix_user {
    uid = 10001
    gid = 10001
  }
  root_directory {
    path = "/anchors"
    creation_info {
      owner_uid   = 10001
      owner_gid   = 10001
      permissions = "0750"
    }
  }
}

resource "aws_efs_file_system_policy" "anchors" {
  file_system_id = aws_efs_file_system.anchors.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "TlsOnly"
        Effect    = "Deny"
        Principal = { AWS = "*" }
        Action    = "*"
        Resource  = aws_efs_file_system.anchors.arn
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      },
      {
        Sid       = "TaskThroughAccessPoint"
        Effect    = "Allow"
        Principal = { AWS = aws_iam_role.task.arn }
        Action    = ["elasticfilesystem:ClientMount", "elasticfilesystem:ClientWrite"]
        Resource  = aws_efs_file_system.anchors.arn
        Condition = { StringEquals = { "elasticfilesystem:AccessPointArn" = aws_efs_access_point.anchors.arn } }
      }
    ]
  })
}

resource "aws_backup_vault" "anchors" {
  name        = "${var.project}-anchors"
  kms_key_arn = aws_kms_key.main.arn
}

# GOVERNANCE-like until changeable_for_days passes, then COMPLIANCE: the lock can no
# longer be removed. Kept short on purpose so a mistaken first apply can be undone.
resource "aws_backup_vault_lock_configuration" "anchors" {
  backup_vault_name   = aws_backup_vault.anchors.name
  min_retention_days  = var.anchor_backup_retention_days
  max_retention_days  = var.anchor_backup_retention_days
  changeable_for_days = 3
}

resource "aws_backup_plan" "anchors" {
  name = "${var.project}-anchors"
  rule {
    rule_name         = "daily"
    target_vault_name = aws_backup_vault.anchors.name
    schedule          = "cron(0 1 * * ? *)"
    lifecycle {
      delete_after = var.anchor_backup_retention_days
    }
  }
}

resource "aws_iam_role" "backup" {
  name = "${var.project}-backup"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "backup.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "backup" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

resource "aws_backup_selection" "anchors" {
  name         = "${var.project}-anchors"
  plan_id      = aws_backup_plan.anchors.id
  iam_role_arn = aws_iam_role.backup.arn
  resources    = [aws_efs_file_system.anchors.arn]
}
