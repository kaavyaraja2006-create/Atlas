package com.atlas.recovery.engine;

/**
 * Candidate recovery strategies compared by the counterfactual simulator (ATLAS X, Milestone 4).
 * Two axes are varied on purpose: WHICH records are found (foreign keys vs recorded provenance)
 * and HOW they are written (whole rows vs single fields), plus a point-in-time restore and the
 * conflict handling of PASCAR. Only E and F can be executed by ATLAS; A to D exist to compare.
 */
public enum RecoveryStrategy {
    FULL_RESTORE("A", "Point-in-time restore",
            "Rewind every record written after the faulty change to its state just before it."),
    NAIVE_FK_ROW("B", "Naive dependency restore (foreign keys, whole rows)",
            "Rewind the origin and every record linked to it by foreign keys to its last version before the fault."),
    VERSION_ROW_RESTORE("C", "Version-based restore (provenance, whole rows)",
            "Rewind every record that consumed the faulty value to the version before it did."),
    FIELD_LEVEL_FK("D", "Field-level repair over foreign keys",
            "Restore the faulty field, then recompute price and total fields of every record linked by foreign keys."),
    PROVENANCE_CONFLICT_BLIND("E", "Provenance-aware selective repair, conflicts ignored",
            "PASCAR's plan without its conflict handling: conflicted fields are silently left as they are."),
    PROVENANCE_CONFLICT_AWARE("F", "Provenance- and conflict-aware selective repair (PASCAR)",
            "PASCAR's plan: field-level repair of what consumed the fault, compensation of final records, "
                    + "and human review of conflicts.");

    private final String letter;
    private final String title;
    private final String description;

    RecoveryStrategy(String letter, String title, String description) {
        this.letter = letter;
        this.title = title;
        this.description = description;
    }

    public String letter() {
        return letter;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    /** Whether ATLAS can execute this strategy through its approval and execution workflow. */
    public boolean executable() {
        return this == PROVENANCE_CONFLICT_AWARE;
    }
}
