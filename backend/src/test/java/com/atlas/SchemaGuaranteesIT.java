package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the same assertion script as scripts/verify-schema.sh, but against
 * the schema that Flyway created when the application started. Proves the
 * migrations as executed by the real app (not just by psql) provide the
 * tenant-isolation, immutability, append-only and hash-chain guarantees.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaGuaranteesIT {

    @Autowired
    PostgreSQLContainer<?> postgres;

    @Test
    void schemaGuaranteesHoldOnFlywayMigratedDatabase() throws Exception {
        postgres.copyFileToContainer(
                MountableFile.forClasspathResource("db/schema-assertions.sql"),
                "/tmp/schema-assertions.sql");

        ExecResult result = postgres.execInContainer(
                "psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                "-v", "ON_ERROR_STOP=1", "-f", "/tmp/schema-assertions.sql");

        // psql writes NOTICE lines (PASS/FAIL) to stderr.
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        assertThat(result.getStderr())
                .contains("ALL SCHEMA ASSERTIONS PASSED")
                .doesNotContain("FAIL ");
    }
}
