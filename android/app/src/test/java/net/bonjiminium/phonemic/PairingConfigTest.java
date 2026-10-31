package net.bonjiminium.phonemic;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PairingConfigTest {
    static String qr(String origin, Object token, Object pin, Object version) throws Exception {
        return new JSONObject().put("serverUrl", origin).put("token", token)
                .put("certificateSha256", pin).put("version", version).toString();
    }
    static PairingConfig example(String origin) throws Exception {
        return PairingConfig.parse(qr(origin, "a".repeat(32), "BC".repeat(32), 1));
    }

    @Test public void validQrProducesPinnedWebsocketAndRoundTrips() throws Exception {
        PairingConfig config = example("https://192.168.1.237:8765/");
        assertEquals("https://192.168.1.237:8765", config.serverUrl);
        assertEquals("bc".repeat(32), config.certificateSha256);
        assertEquals("wss://192.168.1.237:8765/audio?token=" + "a".repeat(32), config.websocketUrl());
        assertEquals(config.serialize(), PairingConfig.parse(config.serialize()).serialize());
        assertEquals("https://pc.local", example("https://PC.local").serverUrl);
    }

    @Test public void unsafeOriginsAreRejected() throws Exception {
        for (String origin : new String[] {"http://pc.local", "wss://pc.local", "file:///tmp/test", "https://user:pass@pc.local",
                "https://pc.local/audio", "https://pc.local/?token=foo", "https://pc.local/#secret",
                "https://pc.local:0", "https://pc.local:65536", "https://pc.local/../audio",
                "https://pc.local\\@attacker.example", "https://pc.local\n", "https://"}) {
            assertThrows(origin, IllegalArgumentException.class, () -> example(origin));
        }
    }

    @Test public void credentialsAndVersionMustHaveExactTypesAndLengths() throws Exception {
        for (Object token : new Object[] {"short", "a".repeat(129), "a".repeat(32) + "&extra=true", 123, JSONObject.NULL}) {
            String text = qr("https://pc.local", token, "ab".repeat(32), 1);
            assertThrows(IllegalArgumentException.class, () -> PairingConfig.parse(text));
        }
        for (Object pin : new Object[] {"ab".repeat(31), "xy".repeat(32), 123, JSONObject.NULL}) {
            String text = qr("https://pc.local", "a".repeat(32), pin, 1);
            assertThrows(IllegalArgumentException.class, () -> PairingConfig.parse(text));
        }
        for (Object version : new Object[] {0, 2, "1", 1.5, true}) {
            String text = qr("https://pc.local", "a".repeat(32), "ab".repeat(32), version);
            assertThrows(IllegalArgumentException.class, () -> PairingConfig.parse(text));
        }
    }

    @Test public void malformedOrOversizeQrIsRejectedWithoutLeakingItsContents() {
        for (String text : new String[] {"sensitive-not-json", "{\"token\":\"sensitive\"}", "x".repeat(4097)}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> PairingConfig.parse(text));
            assertFalse(error.getMessage().contains("sensitive"));
        }
    }
}
