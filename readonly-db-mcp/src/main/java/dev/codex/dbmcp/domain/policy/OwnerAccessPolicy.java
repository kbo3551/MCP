package dev.codex.dbmcp.domain.policy;

import dev.codex.dbmcp.domain.exception.AccessDeniedException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class OwnerAccessPolicy {

    private static final String ALL_OWNERS = "*";
    private static final Pattern ORACLE_IDENTIFIER =
            Pattern.compile("[A-Z][A-Z0-9_$#]{0,127}");

    private final Set<String> allowedOwners;
    private final boolean allOwnersAllowed;

    public OwnerAccessPolicy(Collection<String> allowedOwners) {
        if (allowedOwners == null || allowedOwners.isEmpty()) {
            throw new IllegalArgumentException("allowedOwners must not be empty");
        }

        boolean containsWildcard = allowedOwners.stream()
                .anyMatch(owner -> owner != null && ALL_OWNERS.equals(owner.trim()));
        if (containsWildcard && allowedOwners.size() != 1) {
            throw new IllegalArgumentException(
                    "Wildcard owner must be configured alone");
        }

        this.allOwnersAllowed = containsWildcard;
        this.allowedOwners = new LinkedHashSet<>();
        if (allOwnersAllowed) {
            return;
        }
        for (String owner : allowedOwners) {
            this.allowedOwners.add(normalizeIdentifier(owner, "owner"));
        }
    }

    public String requireAllowed(String owner) {
        if (owner != null && ALL_OWNERS.equals(owner.trim())) {
            if (!allOwnersAllowed) {
                throw new AccessDeniedException("All owners access is not allowed");
            }
            return ALL_OWNERS;
        }
        String normalized = normalizeIdentifier(owner, "owner");
        if (!allOwnersAllowed && !allowedOwners.contains(normalized)) {
            throw new AccessDeniedException("Owner is not allowed: " + normalized);
        }
        return normalized;
    }

    public String requireValidTableName(String tableName) {
        return normalizeIdentifier(tableName, "tableName");
    }

    public String requireConcreteOwner(String owner) {
        String allowedOwner = requireAllowed(owner);
        if (ALL_OWNERS.equals(allowedOwner)) {
            throw new IllegalArgumentException(
                    "A concrete owner is required for this operation");
        }
        return allowedOwner;
    }

    public Set<String> allowedOwners() {
        return Set.copyOf(allowedOwners);
    }

    public boolean allOwnersAllowed() {
        return allOwnersAllowed;
    }

    private String normalizeIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ORACLE_IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be an unquoted database identifier");
        }
        return normalized;
    }
}
