package net.bonjiminium.phonemic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Same 20 ms mono float32 little-endian wire format as the browser. */
public final class AudioPacket {
    public static final int RATE = 48000;
    public static final int SAMPLES = 960;
    public static final int BYTES = SAMPLES * 4;

    public static byte[] encode(short[] pcm) {
        if (pcm.length != SAMPLES) throw new IllegalArgumentException("Expected 960 PCM samples");
        ByteBuffer out = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : pcm) out.putFloat(sample / 32768.0f);
        return out.array();
    }

    private AudioPacket() {}
}
