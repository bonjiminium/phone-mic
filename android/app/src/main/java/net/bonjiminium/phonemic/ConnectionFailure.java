package net.bonjiminium.phonemic;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLException;

/** Safe, actionable messages: never surface URLs, tokens or raw exception text. */
public final class ConnectionFailure {
    public final String message, action;
    private ConnectionFailure(String message, String action) { this.message = message; this.action = action; }
    public static ConnectionFailure classify(Throwable error, int code) {
        if (code == 409) return new ConnectionFailure("Receiver busy. Stop the microphone on another phone or browser, then retry.", "retry");
        if (code == 401) return new ConnectionFailure("Pairing is no longer valid. Open .local/connect.html on your PC and scan its current pairing QR.", "pair");
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificateExpiredException) return new ConnectionFailure(
                    "Your PC’s certificate expired. Run ./run.sh serve on the PC to renew it, then scan the updated pairing QR. Check your phone’s date if this is unexpected.", "pair");
            if (cause instanceof CertificateNotYetValidException) return new ConnectionFailure(
                    "The certificate is not valid yet. Check the date and time on your phone and PC, then retry.", "retry");
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificateException || cause instanceof SSLPeerUnverifiedException) return new ConnectionFailure(
                    "The PC’s identity no longer matches your saved pairing. Verify the address on your own PC, then scan its current pairing QR. Certificate checks were not bypassed.", "pair");
        }
        if (error instanceof SSLException) return new ConnectionFailure(
                "A secure connection could not be established. Check the receiver on your PC and its certificate details. Run ./run.sh doctor for help.", "retry");
        return new ConnectionFailure("Can’t reach your PC. Leave ./run.sh serve running and connect both devices to the same network. Check guest Wi-Fi, VPN and firewall settings; run ./run.sh doctor on the PC for help.", "retry");
    }
}
