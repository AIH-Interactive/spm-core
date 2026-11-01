package dev.spm.core.api;

import java.util.Objects;

/** Logical resource name inside one authorized model domain; never a host path. */
public record ResourceKey(String path) {
    public ResourceKey {
        Objects.requireNonNull(path);
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Resource key must be a relative logical path");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Invalid resource segment");
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) throw new IllegalArgumentException("Control character in resource key");
        }
    }
}
