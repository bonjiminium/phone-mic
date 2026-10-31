package net.bonjiminium.phonemic;

import java.io.IOException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.Test;
import static org.junit.Assert.*;

public class PinnedTrustTest {
    private int request(HeldCertificate certificate, String pin) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false);
            server.enqueue(new MockResponse().setBody("hello"));
            server.start();
            PinnedTrust trust = new PinnedTrust(pin);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trust}, null);
            OkHttpClient client = new OkHttpClient.Builder().sslSocketFactory(context.getSocketFactory(), trust).build();
            try (Response response = client.newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                return response.code();
            } finally {
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdown();
            }
        }
    }

    @Test public void exactPinnedSelfSignedCertificateWorksWithoutInstallingACa() throws Exception {
        HeldCertificate cert = new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();
        assertEquals(200, request(cert, PinnedTrust.fingerprint(cert.certificate())));
    }

    @Test public void wrongPinIsRejected() throws Exception {
        HeldCertificate cert = new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();
        assertThrows(IOException.class, () -> request(cert, "00".repeat(32)));
    }

    @Test public void hostnameIsStillVerifiedEvenWithMatchingPin() throws Exception {
        HeldCertificate cert = new HeldCertificate.Builder().addSubjectAlternativeName("wrong.example").build();
        assertThrows(IOException.class, () -> request(cert, PinnedTrust.fingerprint(cert.certificate())));
    }

    @Test public void expiredCertificateIsRejectedEvenWithMatchingPin() throws Exception {
        long now = System.currentTimeMillis();
        HeldCertificate cert = new HeldCertificate.Builder().addSubjectAlternativeName("localhost")
                .validityInterval(now - 60000, now - 30000).build();
        assertThrows(IOException.class, () -> request(cert, PinnedTrust.fingerprint(cert.certificate())));
    }

    @Test public void malformedPinAndEmptyChainAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PinnedTrust(""));
        assertThrows(CertificateException.class, () -> new PinnedTrust("00".repeat(32)).checkServerTrusted(new java.security.cert.X509Certificate[0], "RSA"));
    }
}
