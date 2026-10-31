package net.bonjiminium.phonemic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in JVM test against the real Windows receiver; does not open its audio WS. */
public class ReceiverIntegrationTest {
    private Properties config() throws Exception {
        String file = System.getenv("PHONE_MIC_RECEIVER_TEST_CONFIG");
        Assume.assumeTrue("Enable with PHONE_MIC_TEST_RECEIVER=1 android/build.sh", file != null);
        Properties properties = new Properties();
        try (java.io.InputStream in = Files.newInputStream(Path.of(file))) { properties.load(in); }
        return properties;
    }

    private int status(Properties config, String pin) throws Exception {
        PinnedTrust trust = new PinnedTrust(pin);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] {trust}, null);
        OkHttpClient client = new OkHttpClient.Builder().sslSocketFactory(tls.getSocketFactory(), trust).build();
        try (Response response = client.newCall(new Request.Builder().url(config.getProperty("statusUrl"))
                .header("Authorization", "Bearer " + config.getProperty("token")).build()).execute()) {
            return response.code();
        } finally {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    @Test public void realWindowsReceiverAcceptsPinnedTlsAndSessionToken() throws Exception {
        Properties config = config();
        assertEquals(200, status(config, config.getProperty("pin")));
    }

    @Test public void realPythonPairingQrParsesAndAuthenticatesWithoutBundledCredentials() throws Exception {
        Properties original = config();
        PairingConfig qr = PairingConfig.parse(original.getProperty("pairingJson"));
        Properties scanned = new Properties();
        scanned.setProperty("statusUrl", qr.serverUrl + "/status");
        scanned.setProperty("token", qr.token);
        assertEquals(200, status(scanned, qr.certificateSha256));
    }

    @Test public void realWindowsReceiverIsRejectedWithWrongPin() throws Exception {
        Properties config = config();
        assertThrows(IOException.class, () -> status(config, "00".repeat(32)));
    }
}
