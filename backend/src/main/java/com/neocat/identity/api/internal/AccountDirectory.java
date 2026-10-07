package com.neocat.identity.api.internal;

/** Minimum account capabilities used by other modules. */
public interface AccountDirectory {
    boolean enabled(long accountId);
    long createInitialSuperAdmin(String username, String rawPassword);
}
