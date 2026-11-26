package com.instagram.user.model;

/** Test-source stand-in for Instagram's profile model, which the extension only finds by its class name. */
public final class User {
    private final String id;

    public User(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
