package ru.self.printlab_backend.stl;

/** Shared upload and parser limits. STL coordinates have no inherent unit. */
public final class StlLimits {
    public static final int MAX_BYTES = 15 * 1024 * 1024;
    public static final double MAX_COORDINATE = 1e12;

    private StlLimits() {}
}
