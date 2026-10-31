package net.bonjiminium.phonemic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioPacketTest {
    @Test public void pcm16ProducesExactBrowserCompatiblePacket() {
        short[] pcm = new short[960];
        pcm[0] = Short.MIN_VALUE;
        pcm[1] = -1;
        pcm[2] = 0;
        pcm[3] = Short.MAX_VALUE;
        byte[] bytes = AudioPacket.encode(pcm);
        assertEquals(3840, bytes.length);
        ByteBuffer floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(-1f, floats.getFloat(), 0f);
        assertEquals(-1 / 32768f, floats.getFloat(), 0f);
        assertEquals(0f, floats.getFloat(), 0f);
        assertEquals(32767 / 32768f, floats.getFloat(), 0f);
        while (floats.hasRemaining()) assertEquals(0f, floats.getFloat(), 0f);
    }

    @Test public void partialPacketsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AudioPacket.encode(new short[959]));
    }
}
