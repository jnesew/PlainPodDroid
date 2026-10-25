package io.github.jnesew.plainpoddroid.core.artwork;

import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;

/** Decides whether source dimensions are safe before Android allocates bitmap pixels. */
public final class ArtworkGeometry {
    private ArtworkGeometry() {
        throw new AssertionError("No instances");
    }

    /** Returns zero when invalid, otherwise a power-of-two sample factor. */
    public static int sampleSize(int width, int height, int target) {
        if (width < 1 || height < 1 || target < 1 || target > 1024
                || width > SecurityLimits.MAX_ARTWORK_DIMENSION
                || height > SecurityLimits.MAX_ARTWORK_DIMENSION
                || (long) width * height > SecurityLimits.MAX_ARTWORK_PIXELS) {
            return 0;
        }
        int sample = 1;
        while ((width + sample - 1) / sample > target
                || (height + sample - 1) / sample > target) {
            sample *= 2;
        }
        return sample;
    }
}
