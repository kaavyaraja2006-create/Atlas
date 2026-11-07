package com.atlas.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atlas.provenance.ProvenanceQueries;
import com.atlas.recovery.engine.ProvenanceRequirements;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The required-provenance rules exist three times: in Java (the engine decides "not reached,
 * provenance complete"), in the incident loader's SQL and in the /provenance/gaps SQL. If they
 * drift, a record could be called complete by one and incomplete by another: this test fails.
 */
class ProvenanceRequirementsDriftTest {

    private static final Pattern ROW = Pattern.compile("\\('(\\w+)',\\s*'(\\w+)',\\s*(true|false),\\s*'([^']*)'\\)");

    private static List<ProvenanceRequirements.Requirement> parse(String sql) {
        List<ProvenanceRequirements.Requirement> out = new ArrayList<>();
        Matcher m = ROW.matcher(sql);
        while (m.find()) {
            out.add(new ProvenanceRequirements.Requirement(m.group(1), m.group(2), Boolean.parseBoolean(m.group(3)), m.group(4)));
        }
        return out;
    }

    @Test
    void theLoaderSqlMatchesTheEngine() {
        assertEquals(ProvenanceRequirements.ALL, parse(RecoveryRepository.LOAD_INCIDENT_GAPS));
    }

    @Test
    void theGapsEndpointSqlMatchesTheEngine() throws Exception {
        Field f = ProvenanceQueries.class.getDeclaredField("PROVENANCE_GAPS");
        f.setAccessible(true);
        assertEquals(ProvenanceRequirements.ALL, parse((String) f.get(null)));
    }
}
