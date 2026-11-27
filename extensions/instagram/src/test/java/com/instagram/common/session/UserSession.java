package com.instagram.common.session;

/**
 * Test-source stand-in for Instagram's {@code UserSession} from the {@code compileOnly} stub module, which is
 * absent from the unit-test runtime classpath.
 */
public class UserSession {
    private final String userId;

    public UserSession() {
        this("");
    }

    public UserSession(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
