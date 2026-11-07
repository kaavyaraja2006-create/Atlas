package com.atlas.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The identity module's public lookup API for other modules. */
@Service
public class UserDirectory {

    public record UserSummary(UUID id, String email, String displayName, boolean active) {
    }

    private final UserRepository users;

    UserDirectory(UserRepository users) {
        this.users = users;
    }

    public Optional<UserSummary> findByEmail(String email) {
        return users.findByEmail(email)
                .map(u -> new UserSummary(u.id(), u.email(), u.displayName(), u.active()));
    }
}
