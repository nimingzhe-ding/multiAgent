package org.example.security;

public final class AuthUserContext {

    private static final ThreadLocal<AuthenticatedUser> CURRENT = new ThreadLocal<>();

    private AuthUserContext() {
    }

    public static void set(AuthenticatedUser user) {
        CURRENT.set(user);
    }

    public static AuthenticatedUser get() {
        return CURRENT.get();
    }

    public static String userIdOrNull() {
        AuthenticatedUser user = CURRENT.get();
        return user == null ? null : user.userId();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
