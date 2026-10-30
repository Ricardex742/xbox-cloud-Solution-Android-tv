package com.world.cloudxsolution;

import org.junit.Test;
import static org.junit.Assert.*;

public class TvCompatibilityProfileTest {
    @Test public void recognizesOnlyTheRequestedModel() {
        assertTrue(TvCompatibilityProfile.isTl042("TL042"));
        assertTrue(TvCompatibilityProfile.isTl042("Multilaser TL-042"));
        assertTrue(TvCompatibilityProfile.isTl042("tl 042"));
        assertFalse(TvCompatibilityProfile.isTl042("55P755"));
        assertFalse(TvCompatibilityProfile.isTl042("TL0420"));
        assertFalse(TvCompatibilityProfile.isTl042("ANDROID TV"));
        assertFalse(TvCompatibilityProfile.isTl042(null));
    }
}
