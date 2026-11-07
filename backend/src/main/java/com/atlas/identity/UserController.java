package com.atlas.identity;

import com.atlas.common.error.ApiException;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    record Me(UUID id, String email, String displayName, boolean platformAdmin) {
    }

    private final AuthService auth;

    UserController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal Jwt jwt) {
        UserAccount user = auth.findUser(UUID.fromString(jwt.getSubject()))
                .filter(UserAccount::active)
                .orElseThrow(() -> ApiException.unauthorized("Your session has ended. Sign in again."));
        return new Me(user.id(), user.email(), user.displayName(), user.platformAdmin());
    }
}
