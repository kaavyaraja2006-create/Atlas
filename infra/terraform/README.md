# ATLAS on AWS

One ECS Fargate service behind an Application Load Balancer, PostgreSQL on RDS,
secrets in Secrets Manager, and the audit anchor log on EFS with locked backups.

```
Internet ──HTTPS──> ALB (+ WAF) ──> ECS task ┬─ web  (Caddy: SPA + strict CSP, :8080)
                                              └─ api  (Spring Boot, :8081, localhost only)
                                                   ├──TLS──> RDS PostgreSQL 16 (private subnets)
                                                   └──NFS/TLS──> EFS /anchors ──daily──> Backup vault (locked)
```

**Status: written and statically checked, never applied.** `tofu validate` passes against
the real AWS provider schema (OpenTofu 1.10.7, provider 6.70.0); Checkov reports 278 passed,
0 failed, 15 skipped, each skip with its reason next to the resource. Nothing here has been
deployed: no account, no apply, no live URL.

## Design choices

| Choice | Why | Cost of the choice |
|---|---|---|
| Web tier and API in **one task** | One origin, no CORS; the API port is never exposed | Both scale together |
| **One task** (`desired_count = 1`) | Rate limits are per instance; exactly one anchor writer | No zero-downtime deploys (old task stops first) |
| Tasks in **public subnets** with a public IP | No NAT gateway (about USD 32/month); inbound only from the ALB's security group | Tasks have an internet route out |
| RDS-**managed** master password | RDS generates and rotates it in Secrets Manager; never in state | The app uses the master user (a least-privilege app role is a follow-up) |
| Anchor log on **EFS** | The threat is a *database* superuser; RDS credentials cannot reach EFS | An AWS account admin could still edit the live file, so... |
| ...**locked backup vault** | Daily copies that cannot be deleted before retention ends, once the lock is past its 3-day grace period | Retention is fixed (default 365 days) |
| WAF on by default | Managed common + bad-input rules, per-IP flood rule | About USD 8/month; `enable_waf = false` to drop it |
| WAF per-IP rule: 2,000 requests / 5 min | Coarse flood protection in front of the app's own limits | A k6 run from one machine gets blocked: load-test with `enable_waf = false` or from several addresses |

Rough monthly cost at list prices: ALB, one 1 vCPU / 2 GB Fargate task and a `db.t4g.micro`
make up most of it, in the order of USD 80–100 with the WAF. **This is an estimate, not a
measurement**; check the AWS Pricing Calculator for your region, and `tofu destroy` when
you are not demoing (set `deletion_protection = false` first).

## First deployment

1. **State bucket** (once): create an S3 bucket with versioning and default encryption, copy
   `backend.hcl.example` to `backend.hcl` and fill it in.
2. **Certificate**: request an ACM certificate for your hostname in the same region and
   validate it (DNS). Copy `terraform.tfvars.example` to `terraform.tfvars`.
3. **Registries first**, so images can be pushed before the service needs them:
   ```
   tofu init -backend-config=backend.hcl
   tofu apply -target='aws_ecr_repository.app'
   ```
4. **Bootstrap images**: build `backend/` and `frontend/` and push them as `:bootstrap` to the
   two repositories in the `ecr_repositories` output (the tag in `terraform.tfvars`).
5. **Everything else**: `tofu apply`. The service will not become healthy until step 6.
6. **Secret values** (once; never committed, never in state):
   ```
   aws secretsmanager put-secret-value --secret-id atlas/jwt-secret --secret-string "$(openssl rand -base64 48)"
   aws secretsmanager put-secret-value --secret-id atlas/metrics-password --secret-string "$(openssl rand -base64 24)"
   ```
   Then `aws ecs update-service --cluster atlas --service atlas --force-new-deployment`.
7. **DNS**: point your hostname at the `url` output (CNAME or Route 53 alias).
8. **Deploys from GitHub**: set repository variables `AWS_REGION` and `AWS_DEPLOY_ROLE_ARN`
   (output `deploy_role_arn`), create a `production` environment, then run the *Deploy*
   workflow. It builds at the commit, pushes immutable SHA tags and rolls the service.

## Turning on audit anchoring

1. Generate a key pair (prints both halves; the private half goes straight into Secrets Manager):
   `java -cp backend/target/classes com.atlas.audit.anchor.AnchorKeys atlas-1`
2. `put-secret-value` the private key into `atlas/anchor-private-key` and
   `atlas-1=<public key>` into `atlas/anchor-public-keys`.
3. Set `anchoring_enabled = true` and `tofu apply`, then deploy.

The application refuses to start if anchoring is enabled with a bad key or no directory.
**Rotation**: add the new public key to the list (`atlas-2=...,atlas-1=...`), switch the key
id and private key, deploy; older anchors keep verifying with the old public key.

## Rotating the JWT secret

Put a new value and force a new deployment. Every access token becomes invalid at once
(users sign in again; refresh tokens are stored hashed in the database and keep working).

## What is not here (yet)

- Prometheus/Grafana in AWS: the CloudWatch alarms cover the edge (p95 latency, 5xx,
  health) and the database; application metrics stay on the internal
  `/actuator/prometheus` endpoint. Amazon Managed Prometheus would be the next step.
- A least-privilege database role for the application (instead of the master user).
- A read-only root filesystem for the API container (the web container already has one).
- Multi-instance operation (shared rate limits, single anchor writer election).
