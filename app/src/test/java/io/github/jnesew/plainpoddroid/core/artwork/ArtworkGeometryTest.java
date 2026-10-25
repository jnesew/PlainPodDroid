package io.github.jnesew.plainpoddroid.core.artwork;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class ArtworkGeometryTest {
    @Test public void samplesAtOrBelowTargetWithoutAllocatingOversizedSource() {
        assertEquals(1, ArtworkGeometry.sampleSize(128, 100, 128));
        assertEquals(4, ArtworkGeometry.sampleSize(400, 301, 128));
        assertEquals(32, ArtworkGeometry.sampleSize(4000, 4000, 128));
        assertEquals(0, ArtworkGeometry.sampleSize(4096, 4096, 128));
        assertEquals(0, ArtworkGeometry.sampleSize(4097, 1, 128));
        assertEquals(0, ArtworkGeometry.sampleSize(0, 12, 128));
        assertEquals(0, ArtworkGeometry.sampleSize(Integer.MAX_VALUE, 4096, 128));
        assertEquals(0, ArtworkGeometry.sampleSize(4096, 4096, 0));
        assertEquals(0, ArtworkGeometry.sampleSize(100, 100, 1025));
    }
}
