package com.atlas.organization;

import com.atlas.audit.ChangeRecorder;
import com.atlas.common.error.ApiException;
import com.atlas.common.tenancy.Actor;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import com.atlas.identity.UserDirectory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrganizationService {

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final UserDirectory users;
    private final ChangeRecorder changes;

    OrganizationService(OrganizationRepository organizations, MembershipRepository memberships,
                        UserDirectory users, ChangeRecorder changes) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.users = users;
        this.changes = changes;
    }

    /** The creator becomes the first ORGANIZATION_ADMIN. */
    @Transactional
    OrganizationRepository.Organization create(UUID userId, String name, String slug) {
        OrganizationRepository.Organization org;
        try {
            org = organizations.insert(slug, name.trim());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("The organization slug '" + slug + "' is already taken.");
        }
        memberships.insertMember(org.id(), userId);
        memberships.insertRole(org.id(), userId, Permissions.ROLE_ORGANIZATION_ADMIN);

        Actor actor = Actor.user(userId);
        changes.recordInsert(org.id(), actor, "organization", org.id(), org.snapshot(), "organization created");
        changes.recordInsert(org.id(), actor, "membership", userId,
                membershipSnapshot(userId, "ACTIVE", List.of(Permissions.ROLE_ORGANIZATION_ADMIN)),
                "creator becomes organization admin");
        return new OrganizationRepository.Organization(org.id(), org.slug(), org.name(), org.currency(),
                org.status(), org.createdAt(), org.version(), List.of(Permissions.ROLE_ORGANIZATION_ADMIN));
    }

    @Transactional(readOnly = true)
    List<OrganizationRepository.Organization> listForUser(UUID userId) {
        return organizations.listForUser(userId);
    }

    @Transactional(readOnly = true)
    PageResponse<MembershipRepository.Member> listMembers(TenantContext tenant, PageQuery query) {
        tenant.require(Permissions.MEMBER_MANAGE);
        return PageResponse.of(
                memberships.list(tenant.organizationId(), query.size(), query.offset()),
                query,
                memberships.count(tenant.organizationId()));
    }

    /** Adds an existing ATLAS user. Email invitations for new users come with email delivery. */
    @Transactional
    MembershipRepository.Member addMember(TenantContext tenant, String email, Set<String> roles) {
        tenant.require(Permissions.MEMBER_MANAGE);
        List<String> sortedRoles = validRoles(roles);
        UserDirectory.UserSummary user = users.findByEmail(email)
                .filter(UserDirectory.UserSummary::active)
                .orElseThrow(() -> ApiException.notFound("User"));
        if (memberships.find(tenant.organizationId(), user.id()).isPresent()) {
            throw ApiException.conflict("That user is already a member of this organization.");
        }
        memberships.insertMember(tenant.organizationId(), user.id());
        sortedRoles.forEach(role -> memberships.insertRole(tenant.organizationId(), user.id(), role));
        changes.recordInsert(tenant.organizationId(), tenant.actor(), "membership", user.id(),
                membershipSnapshot(user.id(), "ACTIVE", sortedRoles), "member added");
        return memberships.find(tenant.organizationId(), user.id()).orElseThrow();
    }

    @Transactional
    MembershipRepository.Member setRoles(TenantContext tenant, UUID userId, Set<String> roles) {
        tenant.require(Permissions.MEMBER_MANAGE);
        List<String> sortedRoles = validRoles(roles);
        memberships.lockOrganization(tenant.organizationId());

        MembershipRepository.Member current = memberships.find(tenant.organizationId(), userId)
                .filter(m -> "ACTIVE".equals(m.status()))
                .orElseThrow(() -> ApiException.notFound("Member"));
        if (new TreeSet<>(current.roles()).equals(new TreeSet<>(sortedRoles))) {
            return current;
        }
        boolean removesAdmin = current.roles().contains(Permissions.ROLE_ORGANIZATION_ADMIN)
                && !sortedRoles.contains(Permissions.ROLE_ORGANIZATION_ADMIN);
        if (removesAdmin && memberships.countOtherActiveAdmins(tenant.organizationId(), userId) == 0) {
            throw ApiException.unprocessable("An organization must keep at least one organization admin.");
        }

        memberships.deleteRolesNotIn(tenant.organizationId(), userId, sortedRoles);
        sortedRoles.forEach(role -> memberships.insertRole(tenant.organizationId(), userId, role));
        long version = memberships.bumpVersion(tenant.organizationId(), userId);
        changes.recordUpdate(tenant.organizationId(), tenant.actor(), "membership", userId, version,
                membershipSnapshot(userId, current.status(), current.roles()),
                membershipSnapshot(userId, current.status(), sortedRoles),
                "roles changed");
        return memberships.find(tenant.organizationId(), userId).orElseThrow();
    }

    Optional<MembershipRepository.Access> access(UUID orgId, UUID userId) {
        return memberships.loadAccess(orgId, userId);
    }

    private List<String> validRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw ApiException.validation(List.of("roles: at least one role is required"));
        }
        Set<String> known = memberships.roleCodes();
        List<String> unknown = roles.stream().filter(r -> !known.contains(r)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw ApiException.validation(List.of("roles: unknown role(s) " + String.join(", ", unknown)));
        }
        return List.copyOf(new TreeSet<>(roles));
    }

    private static Map<String, Object> membershipSnapshot(UUID userId, String status, List<String> roles) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("user_id", userId.toString());
        s.put("status", status);
        s.put("roles", List.copyOf(new TreeSet<>(roles)));
        return s;
    }
}
