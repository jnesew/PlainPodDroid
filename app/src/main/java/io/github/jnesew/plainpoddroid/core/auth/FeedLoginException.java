package io.github.jnesew.plainpoddroid.core.auth;

import java.io.IOException;

public final class FeedLoginException extends IOException {
    private static final long serialVersionUID = 1L;
    public FeedLoginException() { super("Update the podcast login."); }
}
