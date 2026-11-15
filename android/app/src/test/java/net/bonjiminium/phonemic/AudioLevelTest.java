package net.bonjiminium.phonemic;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioLevelTest {
    @Test public void logarithmicScaleMakesQuietSpeechVisibleWithoutBoostingAudio() {
        double[] rms = {0, .0001, .001, .01, .1, 1, 2, Double.NaN, Double.POSITIVE_INFINITY};
        float[] expected = {0, 0, 0, 1f/3, 2f/3, 1, 1, 0, 0};
        for (int i = 0; i < rms.length; i++) assertEquals(expected[i], AudioLevel.fromRms(rms[i]), .00001f);
    }
}
