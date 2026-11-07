# Spring Boot 3.5 → 4.1 migration plan

**Status: PLANNED, not started.** It needs a working Maven build, which this project's
sandbox does not have; it is the first backend task once CI runs.

## Why now

Spring Boot 3.5.16 (June 2026) is the last open-source release of 3.5; its OSS support
ended on 30 June 2026. Any vulnerability found after that will not be fixed in a 3.5 OSS
release. Spring's own advice in the 3.5.16 announcement: *"To continue to receive OSS
support, upgrade to 4.0.x or 4.1.x at your earliest convenience."*

| Line | Released | OSS support ends | Latest (at planning time) |
|---|---|---|---|
| 3.5 | 2025-05 | **2026-06-30 (ended)** | 3.5.16 |
| 4.0 | 2025-11 | 2026-12-31 | 4.0.9 |
| 4.1 | 2026-06 | 2027-07-31 | 4.1.2 |

**Target: 4.1.x**, the line with the longest remaining support.

## Order of work

1. **Green baseline on 3.5 first.** Get `mvn verify` (unit, integration, JaCoCo gate) and the
   CI `stack` job (demo seed, recovery smoke, k6, ZAP) passing on 3.5.16. Without a green
   baseline, a failing test after the upgrade cannot be told apart from an old bug.
2. **Migrate on a branch**, in the steps below, one commit each, running `mvn verify` after each.
3. **Same CI gates** on the branch: integration tests, the SQL harness (unchanged: the SQL
   does not change), the recovery smoke test and k6 must pass with the same thresholds.
4. Merge; Dependabot's ignore rule for the major version (`.github/dependabot.yml`) is then
   moved to 5.x.

## Changes this codebase needs

Found by searching the source against the official migration guide.

| Area | In this codebase | Change |
|---|---|---|
| Java baseline | Java 21 | None (4.x needs 17+) |
| Starters | `spring-boot-starter-web`, `-oauth2-resource-server`, Flyway via `flyway-core` only | `spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-resource-server`, add `spring-boot-starter-flyway` |
| Test starters | `spring-boot-starter-test` + `spring-security-test` | Technology test starters (e.g. `spring-boot-starter-security-test`, `spring-boot-starter-jdbc-test`); they bring `spring-boot-starter-test` |
| Observability | `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp` | `spring-boot-starter-micrometer-metrics` + `spring-boot-starter-opentelemetry`; re-check `management.*` tracing/OTLP property names with the properties migrator |
| Jackson | 7 files import `com.fasterxml.jackson.databind`: 5 in main (`common/json/Json`, the audit event `JsonNode`s, `SecurityConfig` and `RateLimitFilter` error bodies) and 2 in tests; 10 references to `ObjectMapper` in main | Jackson 3: packages become `tools.jackson.*` (annotations keep `com.fasterxml.jackson.annotation`); inject `JsonMapper`. Plan JSON round trip (`PlanJsonRoundTripTest`) and the stored plan fingerprints must be byte-identical before and after: **a changed serialisation would make every stored plan's fingerprint stale.** Fallback: the deprecated `spring-boot-jackson2` module |
| `EnvironmentPostProcessor` | `TestJwtSecret` (`org.springframework.boot.env`) + `spring.factories` entry | Package becomes `org.springframework.boot`; update the import and the `spring.factories` key |
| Health probes | Explicitly enabled | Default in 4.x; the explicit setting can stay |
| Removed test APIs | No `@MockBean`, `MockMvc` or `TestRestTemplate` (RestAssured against a random port) | None expected |
| Spring Security 7 | Resource server with JWT, two filter chains, `hasRole`, custom `RateLimitFilter` | Follow the Security 7 migration guide; `SecurityConfig` is the file to review |
| Nullability | No null-checker in the build | JSpecify annotations appear on Boot APIs; no build impact expected |
| Testcontainers | `org.testcontainers:postgresql`, `junit-jupiter` | Use the versions Boot 4.1 manages; artifact names may change with Testcontainers 2 |

Add `spring-boot-properties-migrator` (runtime scope) while migrating; it reports renamed
properties at startup. Remove it afterwards.

## Risks to watch

- **Plan fingerprints** (above): add a test that loads a plan JSON stored by 3.5 and checks the
  fingerprint is unchanged under 4.1 before touching anything else.
- **Error responses**: `problem+json` bodies are asserted in integration tests; a changed
  Jackson default (e.g. dates, nulls) shows up there first.
- **Security filter order**: `HardeningIT` (rate limit before authentication outcome) and the
  metrics chain must still behave identically.

Sources: [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide),
[Spring Boot 3.5.16 announcement](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/),
[endoflife.date: Spring Boot](https://endoflife.date/spring-boot).
