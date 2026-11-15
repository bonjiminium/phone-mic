package net.bonjiminium.phonemic;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionFailureTest {
    @Test public void expiryIsRecognizedThroughHandshakeCause() {
        SSLHandshakeException error = new SSLHandshakeException("secret URL must not be displayed");
        error.initCause(new CertificateExpiredException());
        ConnectionFailure result = ConnectionFailure.classify(error, 0);
        assertTrue(result.message.contains("expired"));
        assertTrue(result.message.contains("./run.sh serve"));
        assertEquals("pair", result.action);
        assertFalse(result.message.contains("secret"));
    }
    @Test public void changedIdentityNeverSuggestsBypass() {
        assertEquals("pair", ConnectionFailure.classify(new CertificateException(), 0).action);
        assertEquals("pair", ConnectionFailure.classify(new SSLPeerUnverifiedException("private"), 0).action);
        assertTrue(ConnectionFailure.classify(new CertificateException(), 0).message.contains("not bypassed"));
    }
    @Test public void busyRevokedAndNetworkHaveDifferentRecovery() {
        assertEquals("retry", ConnectionFailure.classify(new IOException(), 409).action);
        assertTrue(ConnectionFailure.classify(new IOException(), 409).message.contains("another phone or browser"));
        assertEquals("pair", ConnectionFailure.classify(new IOException(), 401).action);
        assertTrue(ConnectionFailure.classify(new IOException("secret"), 0).message.contains("same network"));
        assertFalse(ConnectionFailure.classify(new IOException("secret"), 0).message.contains("secret"));
    }
}
