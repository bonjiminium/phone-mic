package net.bonjiminium.phonemic;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Untrusted QR input. Only an explicitly confirmed, pinned HTTPS origin is saved. */
public final class PairingConfig {
    public static final int MAX_LENGTH = 4096;
    public final String serverUrl;
    public final String token;
    public final String certificateSha256;

    private PairingConfig(String origin, String token, String pin) {
        this.serverUrl = origin;
        this.token = token;
        this.certificateSha256 = pin;
    }

    public static PairingConfig parse(String text) {
        if (text == null || text.length() > MAX_LENGTH) throw new IllegalArgumentException("Invalid pairing QR size");
        try {
            JSONObject data = new JSONObject(text);
            if (!(data.get("version") instanceof Integer) || data.getInt("version") != 1) {
                throw new IllegalArgumentException("Unsupported pairing QR version");
            }
            Object rawOrigin = data.get("serverUrl");
            Object rawToken = data.get("token");
            Object rawPin = data.get("certificateSha256");
            if (!(rawOrigin instanceof String) || !(rawToken instanceof String) || !(rawPin instanceof String)) {
                throw new IllegalArgumentException("Invalid pairing fields");
            }
            String origin = (String) rawOrigin;
            String token = (String) rawToken;
            String pin = (String) rawPin;
            URI uri = new URI(origin);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null
                    || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Expected an HTTPS receiver origin without credentials or paths");
            }
            if (!token.matches("[A-Za-z0-9_-]{32,128}")) throw new IllegalArgumentException("Invalid pairing token");
            if (!pin.matches("[A-Fa-f0-9]{64}")) throw new IllegalArgumentException("Invalid certificate fingerprint");
            String canonical = "https://" + host.toLowerCase(Locale.ROOT) + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            return new PairingConfig(canonical, token, pin.toLowerCase(Locale.ROOT));
        } catch (JSONException | URISyntaxException e) {
            // Never include QR contents (or parser messages containing the token).
            throw new IllegalArgumentException("Invalid Phone Mic pairing QR");
        }
    }

    public String websocketUrl() {
        return "wss://" + serverUrl.substring("https://".length()) + "/audio?token=" + token;
    }

    public String serialize() {
        try {
            return new JSONObject().put("version", 1).put("serverUrl", serverUrl)
                    .put("token", token).put("certificateSha256", certificateSha256).toString();
        } catch (JSONException e) { throw new IllegalStateException("Could not encode pairing"); }
    }
}
