package net.bonjiminium.phonemic;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

public final class MicService extends Service {
    public static volatile boolean running = false;
    public static volatile String status = "Ready when you are";
    public static volatile String phase = "stopped", recovery = "retry", diagnostics = "";
    public static volatile float level = 0;
    public static volatile long lastAcknowledgedAt = 0, verifiedExpiresAt = 0;
    private static final String CHANNEL = "microphone";
    private static final int NOTIFICATION = 1;
    private final AtomicBoolean ending = new AtomicBoolean(false);
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile AudioRecord recorder;
    private volatile long packets = 0;
    private OkHttpClient client;
    private WebSocket socket;
    private PowerManager.WakeLock wake;
    private boolean started = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @SuppressLint("WakelockTimeout") // Held only for an explicitly started, visible-notification session.
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            finish("Stopped");
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        started = true;
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Microphone permission not granted");
            }
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Microphone streaming", NotificationManager.IMPORTANCE_LOW));
            phase = "connecting";
            startForeground(NOTIFICATION, notification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            running = true;
            recovery = "retry";
            diagnostics = "";
            level = 0;
            lastAcknowledgedAt = 0;
            verifiedExpiresAt = 0;
            status = "Connecting to your Windows PC…";
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneMic:Streaming");
            wake.acquire();
            connect();
            handler.postDelayed(() -> {
                if (!capturing.get()) finish("Connection timed out. Leave ./run.sh serve running on the PC and check both devices share a network.");
            }, 10000);
        } catch (Exception e) {
            diagnostics = "Start: " + e.getClass().getSimpleName();
            finish("Could not start microphone. Check microphone permission in Android app settings, then retry.");
        }
        // Never restart recording automatically after a process kill.
        return START_NOT_STICKY;
    }

    private void connect() throws Exception {
        PairingConfig config = new PairingStore(getNoBackupFilesDir().toPath()).load();
        if (config == null) {
            finish("Not paired. Scan the PC setup QR before starting.");
            return;
        }
        PinnedTrust trust = new PinnedTrust(config.certificateSha256);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] {trust}, null);
        client = new OkHttpClient.Builder()
                .sslSocketFactory(tls.getSocketFactory(), trust)
                // Intentionally retain OkHttp's normal hostname verification.
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
        Request request = new Request.Builder().url(config.websocketUrl()).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                if (ending.get()) { ws.close(1000, "Stopped"); return; }
                // TLS and hostname verification have completed before onOpen.
                try {
                    java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate)
                            response.handshake().peerCertificates().get(0);
                    verifiedExpiresAt = cert.getNotAfter().getTime();
                    new PairingStore(getNoBackupFilesDir().toPath()).save(config.withVerifiedExpiry(verifiedExpiresAt));
                } catch (Exception e) {
                    diagnostics = "Could not save verified certificate expiry";
                }
                ws.send("{\"type\":\"start\",\"sampleRate\":48000,\"channels\":1,\"format\":\"f32le\"}");
            }

            @Override public void onMessage(WebSocket ws, String message) {
                if (ending.get()) return;
                try {
                    JSONObject data = new JSONObject(message);
                    if ("ready".equals(data.optString("type")) && capturing.compareAndSet(false, true)) {
                        new Thread(() -> capture(ws), "phone-mic-capture").start();
                    } else if ("stats".equals(data.optString("type"))) {
                        lastAcknowledgedAt = System.currentTimeMillis();
                        status = "Microphone live — PC is receiving audio";
                        diagnostics = "Packets sent: " + packets
                                + "\nReceiver buffer: " + data.optDouble("queued_ms") + " ms"
                                + "\nAudio gaps: " + data.optInt("underruns")
                                + "\nDropped samples: " + data.optInt("dropped_samples")
                                + "\nDevice errors: " + data.optInt("callback_errors");
                        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification("Live — PC receiving audio. You can lock your phone."));
                    }
                } catch (Exception e) {
                    finish("Receiver sent an invalid response");
                }
            }

            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                if (ending.get()) return;
                ConnectionFailure failure = ConnectionFailure.classify(error, response == null ? 0 : response.code());
                recovery = failure.action;
                diagnostics = "Connection: " + error.getClass().getSimpleName()
                        + (response == null ? "" : " (HTTP " + response.code() + ")");
                finish(failure.message);
            }

            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, null);
                diagnostics = "Receiver close code: " + code;
                finish(code == 1008 ? "Receiver rejected the audio format. Update Phone Mic from your PC’s setup page, then retry."
                        : "The PC closed the connection. Leave ./run.sh serve running, then tap Retry microphone.");
            }

            @Override public void onClosed(WebSocket ws, int code, String reason) {
                finish("Connection closed. Leave ./run.sh serve running on the PC, then tap Retry microphone.");
            }
        });
    }

    @SuppressLint("MissingPermission") // Checked before the microphone foreground service starts.
    private void capture(WebSocket ws) {
        AudioRecord audio = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(AudioPacket.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("48 kHz mono capture unavailable");
            audio = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(AudioPacket.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum, AudioPacket.SAMPLES * 2 * 4))
                    .build();
            recorder = audio;
            if (ending.get()) return;
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Capture not initialized");
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Capture did not start");
            phase = "streaming";
            status = "Microphone capturing — waiting for PC acknowledgement";
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification("Microphone capturing — waiting for PC"));
            short[] pcm = new short[AudioPacket.SAMPLES];
            int used = 0;
            while (!ending.get()) {
                int count = audio.read(pcm, used, pcm.length - used, AudioRecord.READ_BLOCKING);
                if (ending.get()) break;
                if (count <= 0) throw new IllegalStateException("Audio read failed: " + count);
                used += count;
                if (used != pcm.length) continue;
                // Fail explicitly instead of accumulating delayed speech on a dead link.
                if (ws.queueSize() > AudioPacket.BYTES * 10L) {
                    finish("Network stalled; stopped rather than queue delayed audio");
                    break;
                }
                if (!ws.send(ByteString.of(AudioPacket.encode(pcm)))) {
                    finish("Audio connection closed");
                    break;
                }
                double sum = 0;
                for (short sample : pcm) { double value = sample / 32768.0; sum += value * value; }
                level = (float) Math.min(1, Math.sqrt(sum / pcm.length) * 4);
                packets++;
                used = 0;
            }
        } catch (RuntimeException e) {
            if (!ending.get()) {
                diagnostics = "Microphone: " + e.getClass().getSimpleName();
                finish("Could not capture microphone audio. Check Android microphone access and close other recording apps, then retry.");
            }
        } finally {
            if (audio != null) {
                try { audio.stop(); } catch (IllegalStateException ignored) {}
                audio.release();
            }
            recorder = null;
        }
    }

    private Notification notification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MicService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Phone Mic — " + ("streaming".equals(phase) ? "microphone live" : "connecting")).setContentText(message)
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }

    private void finish(String message) {
        if (ending.compareAndSet(false, true)) {
            status = message;
            phase = "Stopped".equals(message) ? "stopped" : "error";
            level = 0;
            running = false;
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        if (ending.compareAndSet(false, true)) {
            status = "Stopped";
            phase = "stopped";
        }
        level = 0;
        running = false;
        handler.removeCallbacksAndMessages(null);
        AudioRecord audio = recorder;
        if (audio != null) {
            try { audio.stop(); } catch (IllegalStateException ignored) {}
        }
        if (socket != null) socket.cancel();
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
