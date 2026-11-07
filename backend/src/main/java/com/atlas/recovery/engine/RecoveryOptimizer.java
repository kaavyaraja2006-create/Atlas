package com.atlas.recovery.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Risk-aware plan selection (ATLAS X, Milestone 5): hard constraints first, then a
 * transparent score, then an explainable risk level. Deterministic and rule-based on
 * purpose; there is nothing to learn here that a reviewer could not read.
 *
 * <p><b>Hard constraints</b> are never traded against the score: a candidate that breaks any
 * of them cannot be recommended, however few writes it makes.
 *
 * <p><b>Score</b> (lower is better), an initial model to be revised by experiment:
 * <pre>
 *   J(P) = w1·C(P) + w2·U(P) + w3·R(P) + w4·T(P)
 *   C = collateral: unaffected records modified + legitimate later updates lost + records deleted
 *   U = unresolved: faulty records left unrepaired + new rule violations + conflicts ignored
 *   R = risk: 3 per compensation (money to or from customers) + 1 per conflict needing a person
 *   T = cost: field writes + 5 per manual step (a person's time weighs more than a write)
 * </pre>
 */
public final class RecoveryOptimizer {

    /** Configurable weights; the defaults make collateral and unresolved faults dominate cost. */
    public record Weights(double collateral, double unresolved, double risk, double cost) {
        public static final Weights DEFAULT = new Weights(10, 10, 2, 0.1);

        public Weights {
            if (collateral < 0 || unresolved < 0 || risk < 0 || cost < 0) {
                throw new IllegalArgumentException("weights must not be negative");
            }
        }
    }

    /** Money thresholds for the financial-impact risk factor (in the organization's currency). */
    public record Thresholds(BigDecimal medium, BigDecimal high, BigDecimal critical, int manyRecords,
                             int veryManyRecords) {
        public static final Thresholds DEFAULT = new Thresholds(new BigDecimal("10000"), new BigDecimal("100000"),
                new BigDecimal("1000000"), 25, 500);
    }

    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    public record Constraint(String code, String description, boolean passed, String detail) {
    }

    public record Score(double total, double collateral, double unresolved, double risk, double cost) {
    }

    public record Candidate(RecoveryStrategy strategy, boolean feasible, List<Constraint> constraints, Score score,
                            int rank) {
    }

    public record RiskFactor(String factor, RiskLevel level, String value, String explanation) {
    }

    /**
     * @param recommended the feasible candidate with the lowest score, or empty when none is feasible
     *                    (then the incident needs a person, and the reason is in the constraints)
     */
    public record Assessment(List<Candidate> candidates, Optional<RecoveryStrategy> recommended, RiskLevel risk,
                             List<RiskFactor> riskFactors, Weights weights, String summary) {
    }

    private final Weights weights;
    private final Thresholds thresholds;

    public RecoveryOptimizer() {
        this(Weights.DEFAULT, Thresholds.DEFAULT);
    }

    public RecoveryOptimizer(Weights weights, Thresholds thresholds) {
        this.weights = Objects.requireNonNull(weights);
        this.thresholds = Objects.requireNonNull(thresholds);
    }

    public Assessment assess(CounterfactualSimulator.Report sim) {
        List<Candidate> scored = new ArrayList<>();
        for (StrategyResult r : sim.results()) {
            List<Constraint> cs = constraints(r);
            boolean feasible = cs.stream().allMatch(Constraint::passed);
            scored.add(new Candidate(r.strategy(), feasible, cs, score(r), 0));
        }
        List<Candidate> ranked = scored.stream()
                .sorted(Comparator.comparing((Candidate c) -> !c.feasible())
                        .thenComparing(c -> !c.constraints().get(0).passed())
                        .thenComparingDouble(c -> c.score().total())
                        .thenComparing(c -> c.strategy().ordinal()))
                .toList();
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Candidate c = ranked.get(i);
            out.add(new Candidate(c.strategy(), c.feasible(), c.constraints(), c.score(), i + 1));
        }
        Optional<RecoveryStrategy> best = out.stream().filter(Candidate::feasible).map(Candidate::strategy).findFirst();

        StrategyResult pascar = sim.results().stream()
                .filter(r -> r.strategy() == RecoveryStrategy.PROVENANCE_CONFLICT_AWARE).findFirst().orElseThrow();
        List<RiskFactor> factors = riskFactors(sim, pascar);
        RiskLevel level = factors.stream().map(RiskFactor::level).max(Comparator.naturalOrder()).orElse(RiskLevel.LOW);
        String summary = best.map(s -> "Recommended: " + s.letter() + " " + s.title() + ". Risk " + level + ".")
                .orElse("No candidate satisfies every hard constraint: this incident needs a person. Risk " + level + ".");
        return new Assessment(out, best, level, factors, weights, summary);
    }

    static List<Constraint> constraints(StrategyResult r) {
        List<Constraint> cs = new ArrayList<>();
        cs.add(new Constraint("APPLICABLE", "The strategy can express this incident", r.applicable(),
                r.applicable() ? "" : r.notApplicableReason()));
        if (!r.applicable()) {
            return cs;
        }
        cs.add(new Constraint("INVARIANTS_HOLD", "Critical business rules hold afterwards", r.invariantViolations() == 0,
                r.invariantViolations() + " new violation(s)" + (r.violations().isEmpty() ? "" : ", e.g. " + r.violations().get(0))));
        cs.add(new Constraint("NO_COLLATERAL", "Records that carry no faulty value are not modified",
                r.collateralModified() == 0, r.collateralModified() + " unaffected record(s) modified"));
        cs.add(new Constraint("PRESERVES_LATER_UPDATES", "Legitimate later updates are preserved",
                r.legitimateOverwritten() == 0, r.legitimateOverwritten() + " later field value(s) overwritten or deleted"));
        cs.add(new Constraint("NO_DELETIONS", "No record is deleted (audit history is never erased)",
                r.recordsDeleted() == 0, r.recordsDeleted() + " record(s) deleted"));
        boolean versioned = r.strategy() == RecoveryStrategy.PROVENANCE_CONFLICT_AWARE
                || r.strategy() == RecoveryStrategy.PROVENANCE_CONFLICT_BLIND;
        cs.add(new Constraint("VERSION_CHECKED_WRITES", "Every write checks the version it was planned against",
                versioned, versioned ? "field writes carry expected versions" : "whole-row restores overwrite whatever is there"));
        cs.add(new Constraint("CONFLICTS_GO_TO_PEOPLE", "Conflicts that need a person are surfaced, not ignored",
                r.conflictsIgnored() == 0, r.conflictsIgnored() + " conflict(s) ignored"));
        cs.add(new Constraint("AUTHORIZED_EXECUTION", "Runs only through the approval and execution workflow",
                r.strategy().executable(), r.strategy().executable() ? "approval, separation of duties, audit"
                : "comparison only: ATLAS has no execution path for this strategy"));
        return cs;
    }

    Score score(StrategyResult r) {
        if (!r.applicable()) {
            return new Score(0, 0, 0, 0, 0);   // not comparable; ranked after every applicable candidate
        }
        double c = r.collateralModified() + r.legitimateOverwritten() + r.recordsDeleted();
        double u = r.unrepaired() + r.invariantViolations() + r.conflictsIgnored();
        double risk = 3.0 * r.compensations() + r.conflictsSurfaced();
        double t = r.fieldWrites() + 5.0 * r.manualSteps();
        double total = weights.collateral() * c + weights.unresolved() * u + weights.risk() * risk + weights.cost() * t;
        return new Score(round(total), c, u, risk, t);
    }

    private List<RiskFactor> riskFactors(CounterfactualSimulator.Report sim, StrategyResult pascar) {
        List<RiskFactor> out = new ArrayList<>();
        BigDecimal money = BigDecimal.ZERO;
        for (RecoveryPlan.Step st : sim.plan().steps()) {
            if (st.amount() != null) {
                money = money.add(st.amount().abs());
            }
            for (RecoveryPlan.FieldChange ch : st.changes()) {
                if (ch.field().equals("total_amount") && ch.current() != null && ch.proposed() != null) {
                    money = money.add(Values.decimal(ch.proposed()).subtract(Values.decimal(ch.current())).abs());
                }
            }
        }
        RiskLevel moneyLevel = money.compareTo(thresholds.critical()) >= 0 ? RiskLevel.CRITICAL
                : money.compareTo(thresholds.high()) >= 0 ? RiskLevel.HIGH
                : money.compareTo(thresholds.medium()) >= 0 ? RiskLevel.MEDIUM : RiskLevel.LOW;
        out.add(new RiskFactor("FINANCIAL_IMPACT", moneyLevel, money.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                "Money moved by the plan: changed order and invoice totals plus compensation amounts."));

        int affected = sim.impact().impacted().size();
        RiskLevel countLevel = affected > thresholds.veryManyRecords() ? RiskLevel.HIGH
                : affected > thresholds.manyRecords() ? RiskLevel.MEDIUM : RiskLevel.LOW;
        out.add(new RiskFactor("AFFECTED_RECORDS", countLevel, Integer.toString(affected),
                "Records the incident reaches; more records, more that can go wrong."));

        long compensations = sim.plan().steps().stream().filter(s -> s.action() == RecoveryPlan.Action.ISSUE_CREDIT_NOTE
                || s.action() == RecoveryPlan.Action.SUPPLEMENTARY_CHARGE).count();
        out.add(new RiskFactor("REVERSIBILITY", compensations > 0 ? RiskLevel.MEDIUM : RiskLevel.LOW,
                compensations + " compensation(s)",
                compensations > 0 ? "Credit notes and charges reach customers and cannot be silently undone."
                        : "Every write is a field correction with its own audit trail."));

        long inferred = sim.impact().records().stream()
                .filter(r -> r.classification() == Classification.REQUIRES_MANUAL_REVIEW).count();
        long unknown = sim.impact().records().stream()
                .filter(r -> r.classification() == Classification.NOT_REACHED_PROVENANCE_INCOMPLETE).count();
        out.add(new RiskFactor("PROVENANCE_COMPLETENESS",
                inferred > 0 ? RiskLevel.HIGH : unknown > 0 ? RiskLevel.MEDIUM : RiskLevel.LOW,
                inferred + " record(s) on inferred or circular provenance, " + unknown
                        + " not reached with missing provenance",
                inferred > 0 ? "Part of the impact rests on evidence that was not recorded at write time."
                        : unknown > 0 ? "Some records next to the impact have unrecorded inputs: impact on them cannot be ruled out."
                        : "Every hop of the impact was recorded at write time."));

        long open = sim.conflicts().stream().filter(FieldConflict::needsReview).count();
        out.add(new RiskFactor("CONFLICTS", open > 0 ? RiskLevel.HIGH : RiskLevel.LOW, open + " needing a person",
                open > 0 ? "Someone changed data the fault reached; recovery must not decide alone."
                        : "Nothing the fault reached was changed by anyone since."));

        int maxDepth = sim.impact().records().stream().mapToInt(AffectedRecord::depth).max().orElse(0);
        out.add(new RiskFactor("DOWNSTREAM_DEPTH", maxDepth > 4 ? RiskLevel.MEDIUM : RiskLevel.LOW,
                Integer.toString(maxDepth), "Longest chain of derived records from the faulty change."));

        out.add(new RiskFactor("SIMULATION", pascar.invariantViolations() > 0 ? RiskLevel.CRITICAL : RiskLevel.LOW,
                pascar.invariantViolations() + " rule violation(s) in the simulated result",
                pascar.invariantViolations() > 0 ? "Executing this plan would break a business rule: do not run it."
                        : "The simulated result keeps every checked business rule."));
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
