package com.atlas.organization;

import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class OrganizationController {

    record CreateOrganizationRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$",
                    message = "must be 3-64 lowercase letters, digits or hyphens") String slug) {
    }

    record AddMemberRequest(@NotBlank @Email @Size(max = 320) String email, @NotEmpty Set<String> roles) {
    }

    record SetRolesRequest(@NotEmpty Set<String> roles) {
    }

    record Access(UUID organizationId, Set<String> roles, Set<String> permissions) {
    }

    private final OrganizationService service;

    OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @PostMapping("/organizations")
    ResponseEntity<OrganizationRepository.Organization> create(@AuthenticationPrincipal Jwt jwt,
                                                               @Valid @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(UUID.fromString(jwt.getSubject()), request.name(), request.slug()));
    }

    @GetMapping("/organizations")
    List<OrganizationRepository.Organization> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.listForUser(UUID.fromString(jwt.getSubject()));
    }

    /** What the caller may do in the selected organization; the UI uses it to hide actions, the server still enforces. */
    @GetMapping("/access")
    Access access(TenantContext tenant) {
        return new Access(tenant.organizationId(), tenant.roles(), tenant.permissions());
    }

    @GetMapping("/members")
    PageResponse<MembershipRepository.Member> members(TenantContext tenant,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size, null, Map.of("displayName", "display_name"), "displayName");
        return service.listMembers(tenant, query);
    }

    @PostMapping("/members")
    ResponseEntity<MembershipRepository.Member> addMember(TenantContext tenant,
                                                          @Valid @RequestBody AddMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.addMember(tenant, request.email(), request.roles()));
    }

    @PutMapping("/members/{userId}/roles")
    MembershipRepository.Member setRoles(TenantContext tenant, @PathVariable UUID userId,
                                         @Valid @RequestBody SetRolesRequest request) {
        return service.setRoles(tenant, userId, request.roles());
    }
}
