# ADR-0006: Defer Kafka, Redis, Kubernetes, Terraform and MFA

**Status:** Accepted (2026-11-05)

**Decision.** These are LATER-tier. Events use a transactional outbox with a
polled in-process publisher first; Kafka is introduced only when a consumer
that needs it exists. Redis is introduced when a load test shows a read path
that needs caching or when rate limiting must be shared across instances.
Deployment targets ECS Fargate before Kubernetes; Terraform arrives with the
first cloud deployment.

**Consequences.** Fewer resume keywords early; every tool that is added later
arrives with a reason and a measurement, which is the stronger interview
story.
