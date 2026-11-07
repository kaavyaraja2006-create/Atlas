resource "aws_db_subnet_group" "main" {
  name       = var.project
  subnet_ids = aws_subnet.private[*].id
}

# TLS required for every connection.
resource "aws_db_parameter_group" "main" {
  name   = "${var.project}-pg16"
  family = "postgres16"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "500" # log statements slower than 500 ms
  }
}

resource "aws_db_instance" "main" {
  #checkov:skip=CKV_AWS_157:Multi-AZ is var.db_multi_az (off by default for cost); RDS backups cover recovery
  #checkov:skip=CKV_AWS_161:The application authenticates with the RDS-managed, rotated password over TLS
  identifier     = var.project
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class
  db_name        = "atlas"
  username       = "atlas"
  # RDS generates and rotates the password in Secrets Manager; it never appears in state.
  manage_master_user_password   = true
  master_user_secret_kms_key_id = aws_kms_key.main.arn

  allocated_storage     = 20
  max_allocated_storage = 100
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.main.arn

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  parameter_group_name   = aws_db_parameter_group.main.name
  publicly_accessible    = false
  multi_az               = var.db_multi_az

  backup_retention_period             = 7
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.deletion_protection
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${var.project}-final"
  auto_minor_version_upgrade          = true
  enabled_cloudwatch_logs_exports     = ["postgresql"]
  performance_insights_enabled        = true
  performance_insights_kms_key_id     = aws_kms_key.main.arn
  iam_database_authentication_enabled = false
  monitoring_interval                 = 60
  monitoring_role_arn                 = aws_iam_role.rds_monitoring.arn
}

resource "aws_iam_role" "rds_monitoring" {
  name = "${var.project}-rds-monitoring"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "monitoring.rds.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}
