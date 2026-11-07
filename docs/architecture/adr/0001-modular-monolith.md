# ADR-0001: Start as a modular monolith

**Status:** Accepted (2026-11-05)

**Context.** The original plan listed nine modules and Kafka, Kubernetes and
Terraform. One developer is building it alongside coursework. Microservices
would add network failure modes, distributed transactions and deployment
overhead before any business logic exists.

**Decision.** One Spring Boot application, one PostgreSQL database, packages
per module with an enforced boundary rule (public services/DTOs only). A
module is extracted only with a written, measured reason (scaling profile,
isolation, release cadence).

**Consequences.** Recovery can rely on ordinary ACID transactions spanning
business rows, change events and provenance edges — which is what makes
"provenance recorded in the same transaction" cheap and correct. If
extraction happens later, the outbox (Phase 7) becomes the integration seam.
