package com.atlas.organization;

import com.atlas.common.error.ApiException;
import com.atlas.common.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Turns the X-Organization-Id header plus the signed-in user into a
 * TenantContext, by asking the database whether this user is an active
 * member and what their roles grant. A non-member gets 404, exactly as if
 * the organization did not exist.
 */
@Component
class TenantContextResolver implements HandlerMethodArgumentResolver {

    static final String HEADER = "X-Organization-Id";

    private final OrganizationService organizations;

    TenantContextResolver(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return TenantContext.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            throw ApiException.badRequest("The " + HEADER + " header is required.");
        }
        UUID orgId;
        try {
            orgId = UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("The " + HEADER + " header must be a UUID.");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw ApiException.unauthorized("Sign in to access this resource.");
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        MembershipRepository.Access access = organizations.access(orgId, userId)
                .orElseThrow(() -> ApiException.notFound("Organization"));
        return new TenantContext(orgId, userId, access.roles(), access.permissions());
    }
}
