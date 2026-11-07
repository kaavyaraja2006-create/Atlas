output "url" {
  description = "Point your DNS name (the certificate's) at this load balancer."
  value       = aws_lb.main.dns_name
}

output "ecr_repositories" {
  value = { for k, r in aws_ecr_repository.app : k => r.repository_url }
}

output "secrets_to_fill" {
  description = "Set these values once before the first deploy (README.md in this folder)."
  value = {
    jwt                = aws_secretsmanager_secret.jwt.arn
    metrics_password   = aws_secretsmanager_secret.metrics_password.arn
    anchor_private_key = aws_secretsmanager_secret.anchor_private_key.arn
    anchor_public_keys = aws_secretsmanager_secret.anchor_public_keys.arn
  }
}

output "deploy_role_arn" {
  value = var.github_repository == "" ? null : aws_iam_role.deploy[0].arn
}

output "ecs" {
  value = { cluster = aws_ecs_cluster.main.name, service = aws_ecs_service.app.name, family = aws_ecs_task_definition.app.family }
}
