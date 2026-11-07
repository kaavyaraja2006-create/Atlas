variable "project" {
  description = "Name prefix for every resource."
  type        = string
  default     = "atlas"
}

variable "region" {
  description = "AWS region."
  type        = string
  default     = "ap-south-1"
}

variable "certificate_arn" {
  description = "ACM certificate for the public hostname (HTTPS only; HTTP redirects)."
  type        = string
}

variable "api_image" {
  description = "Backend image, e.g. <account>.dkr.ecr.<region>.amazonaws.com/atlas-api:<git-sha>."
  type        = string
}

variable "web_image" {
  description = "Web tier image (SPA + Caddy), same registry, same tag as the backend."
  type        = string
}

variable "db_instance_class" {
  description = "RDS instance class. db.t4g.micro is free-tier eligible in many accounts."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_multi_az" {
  description = "Standby in a second AZ (doubles database cost)."
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = "Protect the database and load balancer from accidental deletion."
  type        = bool
  default     = true
}

variable "task_cpu" {
  type    = number
  default = 1024
}

variable "task_memory" {
  type    = number
  default = 2048
}

variable "alarm_email" {
  description = "Receives CloudWatch alarms (must confirm the SNS subscription). Empty: no subscription."
  type        = string
  default     = ""
}

variable "anchoring_enabled" {
  description = "Audit anchoring (needs the anchor secrets set first; see README in this folder)."
  type        = bool
  default     = false
}

variable "anchor_backup_retention_days" {
  description = "How long locked backups of the anchor log are kept."
  type        = number
  default     = 365
}

variable "github_repository" {
  description = "owner/repo allowed to deploy through GitHub OIDC (empty: no deploy role)."
  type        = string
  default     = ""
}

variable "enable_waf" {
  description = "AWS WAF on the load balancer: managed common + bad-input rules and a per-IP rate rule (about USD 8/month)."
  type        = bool
  default     = true
}
