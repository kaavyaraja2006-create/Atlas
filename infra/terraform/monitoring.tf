resource "aws_sns_topic" "alarms" {
  name              = "${var.project}-alarms"
  kms_master_key_id = aws_kms_key.main.arn
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alarm_email == "" ? 0 : 1
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

locals {
  alb_dimensions = { LoadBalancer = aws_lb.main.arn_suffix }
}

# NFR-PERF-1 as a standing check at the edge: p95 response time over 300 ms.
resource "aws_cloudwatch_metric_alarm" "latency_p95" {
  alarm_name          = "${var.project}-p95-latency"
  alarm_description   = "p95 target response time above 300 ms for 15 minutes (NFR-PERF-1)"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  extended_statistic  = "p95"
  dimensions          = merge(local.alb_dimensions, { TargetGroup = aws_lb_target_group.web.arn_suffix })
  period              = 300
  evaluation_periods  = 3
  threshold           = 0.3
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "server_errors" {
  alarm_name          = "${var.project}-5xx"
  alarm_description   = "More than 10 server errors in 5 minutes"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  dimensions          = local.alb_dimensions
  period              = 300
  evaluation_periods  = 1
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "unhealthy" {
  alarm_name          = "${var.project}-unhealthy"
  alarm_description   = "No healthy task behind the load balancer"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  statistic           = "Minimum"
  dimensions          = merge(local.alb_dimensions, { TargetGroup = aws_lb_target_group.web.arn_suffix })
  period              = 60
  evaluation_periods  = 5
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "db_cpu" {
  alarm_name          = "${var.project}-db-cpu"
  alarm_description   = "Database CPU above 80 % for 15 minutes"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "db_storage" {
  alarm_name          = "${var.project}-db-storage"
  alarm_description   = "Less than 2 GB of database storage left"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Minimum"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.main.identifier }
  period              = 300
  evaluation_periods  = 1
  threshold           = 2147483648
  comparison_operator = "LessThanThreshold"
  alarm_actions       = [aws_sns_topic.alarms.arn]
}
