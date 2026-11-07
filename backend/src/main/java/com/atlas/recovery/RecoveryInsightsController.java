package com.atlas.recovery;

import com.atlas.common.tenancy.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ATLAS X: conflict intelligence (Milestone 3), counterfactual simulation (4) and plan assessment (5). */
@RestController
@RequestMapping("/api/v1/recovery")
class RecoveryInsightsController {

    record ReviewRequest(@NotBlank @Size(max = 64) String entityType, @NotNull UUID entityId,
                         @NotBlank @Size(max = 64) String field,
                         @NotNull @Pattern(regexp = "KEEP_LATER_CHANGE|RECOVERY_MAY_OVERWRITE|NEEDS_INVESTIGATION")
                         String decision,
                         @NotBlank @Size(min = 3, max = 1000) String note) {
    }

    private final ConflictService conflicts;
    private final SimulationService simulations;

    RecoveryInsightsController(ConflictService conflicts, SimulationService simulations) {
        this.conflicts = conflicts;
        this.simulations = simulations;
    }

    /**
     * Runs every candidate strategy on an isolated in-memory copy of a consistent snapshot,
     * then the optimiser (hard constraints, score, risk). Writes only simulation tables.
     */
    @PostMapping("/incidents/{id}/simulations")
    @ResponseStatus(HttpStatus.CREATED)
    SimulationService.RunView simulate(TenantContext tenant, @PathVariable UUID id) {
        return simulations.simulate(tenant, id);
    }

    @GetMapping("/incidents/{id}/simulations")
    java.util.List<SimulationRepository.Run> simulations(TenantContext tenant, @PathVariable UUID id) {
        return simulations.list(tenant, id);
    }

    /** One run with every strategy's results and the optimiser's assessment (the comparison). */
    @GetMapping("/simulations/{id}")
    SimulationService.RunView simulation(TenantContext tenant, @PathVariable UUID id) {
        return simulations.get(tenant, id);
    }

    /**
     * A strategy's stored record details, one page at a time. The totals are exact; when the
     * response says {@code partial}, the details were cut at the limit and are not complete.
     */
    @GetMapping("/simulations/{id}/strategies/{strategy}/records")
    SimulationService.RecordsPage records(TenantContext tenant, @PathVariable UUID id, @PathVariable String strategy,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "100") int size) {
        return simulations.records(tenant, id, strategy, category, page, size);
    }

    /** Every stored pairwise comparison of a simulation (immutable; computed from complete record sets). */
    @GetMapping("/simulations/{id}/comparisons")
    java.util.List<SimulationRepository.ComparisonRow> comparisons(TenantContext tenant, @PathVariable UUID id) {
        return simulations.comparisons(tenant, id);
    }

    @GetMapping("/simulations/{id}/comparisons/{a}/{b}")
    SimulationRepository.ComparisonRow comparison(TenantContext tenant, @PathVariable UUID id, @PathVariable String a,
                                                  @PathVariable String b) {
        return simulations.comparison(tenant, id, a, b);
    }

    /** Risk, constraints and score for a plan, from the latest simulation; says when that is stale. */
    @GetMapping("/plans/{id}/assessment")
    SimulationService.PlanAssessment assessment(TenantContext tenant, @PathVariable UUID id) {
        return simulations.assessment(tenant, id);
    }

    /** Field-level conflicts of the incident, with evidence and the latest review of each. */
    @GetMapping("/incidents/{id}/conflicts")
    ConflictService.IncidentConflicts conflicts(TenantContext tenant, @PathVariable UUID id) {
        return conflicts.conflicts(tenant, id);
    }

    /** Records a person's decision on one conflict. Append-only: a new decision supersedes the old. */
    @PostMapping("/incidents/{id}/conflicts/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    ConflictRepository.Review review(TenantContext tenant, @PathVariable UUID id,
                                     @Valid @RequestBody ReviewRequest request) {
        return conflicts.review(tenant, id, request.entityType(), request.entityId(), request.field(),
                request.decision(), request.note());
    }
}
