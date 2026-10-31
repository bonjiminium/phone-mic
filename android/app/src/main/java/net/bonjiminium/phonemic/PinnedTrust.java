package net.bonjiminium.phonemic;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;

/** Trust exactly one leaf certificate, not a CA and not the user's trust store.
 * OkHttp's DEFAULT hostname verification must remain enabled separately.
 */
public final class PinnedTrust implements X509TrustManager {
    private final byte[] expected;

    public PinnedTrust(String hexSha256) {
        // Avoid HexFormat at runtime: Android's Java library availability varies.
        if (hexSha256 == null || !hexSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Expected a SHA-256 certificate fingerprint");
        }
        expected = new byte[32];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) Integer.parseInt(hexSha256.substring(i * 2, i * 2 + 2), 16);
        }
    }

    public static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("Missing server certificate");
        chain[0].checkValidity();
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
            if (!MessageDigest.isEqual(expected, actual)) throw new CertificateException("Server certificate pin mismatch");
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }

    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Not a client-certificate trust manager");
    }

    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
}
