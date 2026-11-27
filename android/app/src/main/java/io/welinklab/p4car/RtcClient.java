package io.welinklab.p4car;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RTCStats;
import org.webrtc.RtpSender;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.net.ssl.SSLException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class RtcClient {
    enum Microphone { OFF, STARTING, ON }
    enum Connection { IDLE, CONNECTING, LIVE }

    interface Listener {
        void connection(String message, Connection state);
        void steering(boolean enabled);
        void lights(boolean ready, boolean headlight, boolean pending);
        void audio(boolean ready, boolean speaker, Microphone microphone);
        void quality(boolean standard, boolean canSwitch);
        void microphoneError(boolean resetAudio);
    }

    private static final int DIRECT_OFFER_TIMEOUT = 6500;
    private static final int RELAY_OFFER_TIMEOUT = 18000;
    private static final int ICE_GATHER_TIMEOUT = 6500;
    private static final int ICE_GRACE = 250;
    private static final int MIC_START_TIMEOUT = 3500;
    private static boolean webrtcInitialized;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final Listener listener;
    private final NativeVideoView video;
    private final EglBase egl;
    private final JavaAudioDeviceModule audioDevice;
    private final PeerConnectionFactory factory;
    private final AudioManager audioManager;
    private final Runnable connectAgain = this::openSocket;
    private final Runnable microphoneTimeout = () -> failMicrophone(true);
    private final AtomicInteger pendingCapture = new AtomicInteger();
    private final AtomicBoolean enablePhysicalCapture = new AtomicBoolean();
    private ServerProfile profile;
    private WebSocket socket;
    private Call call;
    private Runnable sessionRetry;
    private boolean socketOpen;
    private boolean relayOnlyRetry;
    private boolean foreground;
    private boolean standard;
    private boolean speaker;
    private Microphone microphone = Microphone.OFF;
    private volatile int captureGeneration;
    private boolean routedAudio;
    private int previousAudioMode;
    private boolean previousSpeakerphone;

    RtcClient(Context context, NativeVideoView video, Listener listener) {
        this.video = video;
        this.listener = listener;
        Context app = context.getApplicationContext();
        audioManager = app.getSystemService(AudioManager.class);
        if (!webrtcInitialized) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app)
                    .setEnableInternalTracer(false).createInitializationOptions());
            webrtcInitialized = true;
        }
        egl = EglBase.create();
        video.init(egl.getEglBaseContext());
        audioDevice = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioBufferCallback((buffer, format, channels, sampleRate, bytes, timestamp) ->
                        onAudioBuffer(bytes, buffer.capacity(), timestamp))
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    @Override public void onWebRtcAudioRecordInitError(String error) { reportMicError(error); }
                    @Override public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code,
                                                                        String error) { reportMicError(error); }
                    @Override public void onWebRtcAudioRecordError(String error) { reportMicError(error); }
                }).createAudioDeviceModule();
        // SDP negotiation may initialize the input before Android grants mic access.
        audioDevice.setAudioRecordEnabled(false);
        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDevice)
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    void connect(ServerProfile next) {
        if (next.sameAs(profile)) return;
        disconnect();
        profile = next;
        openSocket();
    }

    void reconnect() {
        ServerProfile next = profile;
        disconnect();
        if (next != null) connect(next);
    }

    void endCommunication() {
        speaker = false;
        disconnect();
    }

    private void openSocket() {
        if (profile == null) return;
        relayOnlyRetry = false;
        listener.connection("正在连接服务器", Connection.CONNECTING);
        Request request = new Request.Builder().url(profile.signalUrl())
                .header("Origin", profile.origin()).header("Sec-WebSocket-Protocol", "p4-v1").build();
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                main.post(() -> { if (socket == ws) socketOpen = true; });
            }
            @Override public void onMessage(WebSocket ws, String message) {
                main.post(() -> { if (socket == ws) receive(message); });
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, null);
                boolean busy = code == 4001;
                main.post(() -> socketEnded(ws, busy ? "已有操作者在线，稍后重试" : "连接已断开，正在重试",
                        busy ? Connection.IDLE : Connection.CONNECTING, busy ? 3500 : 1500));
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                boolean denied = response != null && response.code() == 403;
                boolean invalidCertificate = error instanceof SSLException;
                String message = invalidCertificate ? "服务器证书校验失败"
                        : denied ? "服务器拒绝连接" : "服务器连接失败，正在重试";
                boolean retry = !denied && !invalidCertificate;
                main.post(() -> socketEnded(ws, message, retry ? Connection.CONNECTING : Connection.IDLE, retry ? 2000 : 0));
            }
        });
    }

    private void socketEnded(WebSocket ws, String message, Connection state, int retryMs) {
        if (socket != ws) return;
        socket = null;
        socketOpen = false;
        cancelSessionRetry();
        closeCall();
        listener.connection(message, state);
        if (retryMs > 0) main.postDelayed(connectAgain, retryMs);
    }

    private void receive(String data) {
        JSONObject message;
        try { message = new JSONObject(data); }
        catch (JSONException ignored) { return; }
        String type = message.optString("type");
        if ("offline".equals(type)) {
            cancelSessionRetry();
            closeCall();
            relayOnlyRetry = false;
            listener.connection("开发板离线，等待上线", Connection.IDLE);
            return;
        }
        if ("ready".equals(type)) {
            begin(message);
            return;
        }
        Call active = call;
        if (active == null || !active.id.equals(message.optString("id"))) return;
        switch (type) {
            case "offer": offer(active, message.optString("sdp")); break;
            case "candidate": {
                IceCandidate candidate = new IceCandidate("", 0, message.optString("candidate"));
                if (active.remoteSet) active.peer.addIceCandidate(candidate);
                else active.remoteCandidates.add(candidate);
                break;
            }
            case "steering-ready":
                active.steering = true;
                listener.steering(active.connected);
                break;
            case "lights-ready":
                active.lights = true;
                updateLights();
                break;
            case "headlight-state":
                if (!active.lights) break;
                active.headlight = message.optBoolean("on");
                active.headlightPending = false;
                main.removeCallbacks(active.headlightTimeout);
                updateLights();
                break;
            case "video-quality":
                standard = "sd".equals(message.optString("quality"));
                listener.quality(standard, !"hd-only".equals(message.optString("quality")));
                break;
            case "failed": restart(active, true); break;
        }
    }

    private void begin(JSONObject message) {
        cancelSessionRetry();
        closeCall();
        List<PeerConnection.IceServer> stun = servers(message.optJSONArray("stunServers"));
        List<PeerConnection.IceServer> turn = servers(message.optJSONArray("iceServers"));
        boolean direct = profile.directOnly || turn.isEmpty();
        if (direct && stun.isEmpty()) {
            listener.connection("服务器未提供 P2P 直连配置", Connection.IDLE);
            return;
        }
        List<PeerConnection.IceServer> candidates = new ArrayList<>();
        if (direct || !relayOnlyRetry) candidates.addAll(stun);
        if (!direct) candidates.addAll(turn);
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(candidates);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.iceTransportsType = !direct && relayOnlyRetry
                ? PeerConnection.IceTransportsType.RELAY : PeerConnection.IceTransportsType.ALL;
        Call active = new Call(message.optString("id"), direct);
        call = active;
        active.peer = factory.createPeerConnection(config, new PeerEvents(active));
        if (active.peer == null) {
            restart(active, true);
            return;
        }
        active.peer.setAudioRecording(false);
        active.peer.setAudioPlayout(false);
        listener.connection(direct ? "正在尝试 P2P 直连" : relayOnlyRetry ? "正在建立 TURN 中继" : "正在协商连接路径",
                Connection.CONNECTING);
        send(object("type", "start", "id", active.id, "mode", direct ? "direct" : "relay",
                "quality", standard ? "sd" : "hd"));
        main.postDelayed(active.watchdog, direct ? DIRECT_OFFER_TIMEOUT : RELAY_OFFER_TIMEOUT);
    }

    private static List<PeerConnection.IceServer> servers(JSONArray entries) {
        List<PeerConnection.IceServer> result = new ArrayList<>();
        if (entries == null) return result;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            JSONArray list = entry.optJSONArray("urls");
            List<String> urls = new ArrayList<>();
            if (list != null) {
                for (int j = 0; j < list.length(); j++) {
                    String uri = list.optString(j);
                    if (!uri.isEmpty()) urls.add(uri);
                }
            } else if (!entry.optString("urls").isEmpty()) urls.add(entry.optString("urls"));
            if (!urls.isEmpty()) result.add(PeerConnection.IceServer.builder(urls)
                    .setUsername(entry.optString("username")).setPassword(entry.optString("credential")).createIceServer());
        }
        return result;
    }

    private void offer(Call active, String sdp) {
        if (active.answering || !sdp.startsWith("v=0")) return;
        active.answering = true;
        logTiming(active, "offer");
        active.peer.setRemoteDescription(new AnswerObserver(active) {
            @Override public void onSetSuccess() { main.post(() -> prepareAnswer(active)); }
        }, new SessionDescription(SessionDescription.Type.OFFER, sdp));
    }

    private void prepareAnswer(Call active) {
        if (call != active) return;
        active.remoteSet = true;
        for (IceCandidate candidate : active.remoteCandidates) active.peer.addIceCandidate(candidate);
        active.remoteCandidates.clear();
        for (RtpTransceiver transceiver : active.peer.getTransceivers()) {
            if (transceiver.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) {
                transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                active.microphoneSender = transceiver.getSender();
            }
        }
        active.peer.createAnswer(new AnswerObserver(active) {
            @Override public void onCreateSuccess(SessionDescription description) {
                main.post(() -> {
                    if (call == active) active.peer.setLocalDescription(new AnswerObserver(active) {
                        @Override public void onSetSuccess() { main.post(() -> answerSet(active)); }
                    }, description);
                });
            }
        }, new MediaConstraints());
    }

    private void answerSet(Call active) {
        if (call != active) return;
        active.localSet = true;
        main.removeCallbacks(active.watchdog);
        main.postDelayed(active.iceDeadline, ICE_GATHER_TIMEOUT);
        gather(active);
    }

    private void gather(Call active) {
        if (call != active || !active.localSet || active.answerSent) return;
        if (active.peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            sendAnswer(active);
            return;
        }
        SessionDescription local = active.peer.getLocalDescription();
        if (!active.graceScheduled && local != null && local.description.contains(active.direct ? " typ srflx" : " typ relay")) {
            active.graceScheduled = true;
            main.postDelayed(active.iceDeadline, ICE_GRACE);
        }
    }

    private void sendAnswer(Call active) {
        if (call != active || active.answerSent) return;
        SessionDescription local = active.peer.getLocalDescription();
        if (local == null) { restart(active, true); return; }
        active.answerSent = true;
        main.removeCallbacks(active.iceDeadline);
        send(object("type", "answer", "id", active.id, "sdp", local.description));
        logTiming(active, "answer");
        if (!active.connected) main.postDelayed(active.watchdog, active.direct ? 7500 : 14000);
    }

    private void peerState(Call active, PeerConnection.PeerConnectionState state) {
        if (call != active) return;
        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
            active.connected = true;
            main.removeCallbacks(active.watchdog);
            String route = active.direct ? "P2P" : active.route;
            listener.connection(route == null ? "实时画面" : "实时画面 · " + route, Connection.LIVE);
            logTiming(active, "connected");
            if (!active.direct) reportSelectedPath(active, 3);
            listener.steering(active.steering);
            updateLights();
            updateAudio();
        } else if (state == PeerConnection.PeerConnectionState.DISCONNECTED) {
            neutral(active);
            active.connected = false;
            listener.steering(false);
            updateLights();
            listener.connection("连接波动，正在恢复", Connection.CONNECTING);
            setMicrophone(false);
            main.removeCallbacks(active.watchdog);
            main.postDelayed(active.watchdog, 4500);
        } else if (state == PeerConnection.PeerConnectionState.FAILED) restart(active, true);
    }

    private void reportSelectedPath(Call active, int checksLeft) {
        if (call != active || !active.connected) return;
        active.peer.getStats(report -> {
            String route = selectedRoute(report.getStatsMap());
            main.post(() -> {
                if (call != active || !active.connected) return;
                if (route != null && !route.equals(active.route)) {
                    active.route = route;
                    listener.connection("实时画面 · " + route, Connection.LIVE);
                    logTiming(active, "selected " + route);
                }
                if (checksLeft > 1) main.postDelayed(() -> reportSelectedPath(active, checksLeft - 1), 700);
            });
        });
    }

    private static String selectedRoute(Map<String, RTCStats> stats) {
        for (RTCStats transport : stats.values()) {
            if (!"transport".equals(transport.getType())) continue;
            RTCStats pair = stats.get(transport.getMembers().get("selectedCandidatePairId"));
            if (pair == null) continue;
            RTCStats local = stats.get(pair.getMembers().get("localCandidateId"));
            RTCStats remote = stats.get(pair.getMembers().get("remoteCandidateId"));
            if (local == null || remote == null) continue;
            Object localType = local.getMembers().get("candidateType");
            Object remoteType = remote.getMembers().get("candidateType");
            if (localType == null || remoteType == null) continue;
            return "relay".equals(localType) || "relay".equals(remoteType) ? "TURN" : "P2P";
        }
        return null;
    }

    private static void logTiming(Call active, String phase) {
        Log.i("P4Rtc", phase + " after " + (SystemClock.elapsedRealtime() - active.startedAt) + " ms");
    }

    private void receiveVideo(Call active, MediaStreamTrack track) {
        if (call != active || !(track instanceof VideoTrack)) return;
        if (active.receivedVideo == track) return;
        if (active.receivedVideo != null) active.receivedVideo.removeSink(video);
        active.receivedVideo = (VideoTrack) track;
        active.receivedVideo.addSink(video);
    }

    void sendSteering(int position) {
        Call active = call;
        if (active != null && active.steering) sendDrive(active, "steer", position);
    }

    void sendThrottle(int position) {
        Call active = call;
        if (active != null && active.lights) sendDrive(active, "throttle", Math.min(0, position));
    }

    private void sendDrive(Call active, String type, int position) {
        if (!active.connected || (position != 0 && !foreground)) return;
        if (position != 0 && socket != null && socket.queueSize() > 1024) return;
        send(object("type", type, "id", active.id, "position", position));
    }

    private void neutral(Call active) {
        if (active.steering) sendDrive(active, "steer", 0);
        if (active.lights) sendDrive(active, "throttle", 0);
    }

    void toggleHeadlight() {
        Call active = call;
        if (active == null || !active.connected || !active.lights || active.headlightPending || !foreground) return;
        if (!send(object("type", "headlight", "id", active.id, "on", !active.headlight))) return;
        active.headlightPending = true;
        updateLights();
        main.postDelayed(active.headlightTimeout, 2000);
    }

    private void updateLights() {
        Call active = call;
        boolean ready = active != null && active.connected && active.lights;
        listener.lights(ready, ready && active.headlight, ready && active.headlightPending);
    }

    void setStandard(boolean next) {
        if (standard == next) return;
        standard = next;
        listener.quality(standard, true);
        if (call != null) restart(call, false);
    }

    private void restart(Call active, boolean fallback) {
        if (call != active) return;
        if (fallback && !active.direct) relayOnlyRetry = true;
        String id = active.id;
        closeCall();
        listener.connection(fallback && relayOnlyRetry ? "正在切换 TURN 中继" : "正在重新建立连接", Connection.CONNECTING);
        cancelSessionRetry();
        sessionRetry = () -> send(object("type", "restart", "id", id));
        main.postDelayed(sessionRetry, fallback && profile.directOnly ? 2500 : 180);
    }

    private void cancelSessionRetry() {
        if (sessionRetry != null) main.removeCallbacks(sessionRetry);
        sessionRetry = null;
    }

    void setForeground(boolean visible) {
        foreground = visible;
        if (!visible) {
            if (call != null) neutral(call);
            stopMicrophone();
        }
        updateAudio();
    }

    void toggleSpeaker() {
        speaker = !speaker;
        updateAudio();
    }

    boolean isSpeakerEnabled() { return speaker; }

    boolean isMicrophoneRequested() { return microphone != Microphone.OFF; }

    void setMicrophone(boolean enabled) {
        Call active = call;
        if (!enabled) {
            stopMicrophone();
            updateAudio();
            return;
        }
        if (isMicrophoneRequested() || !foreground || active == null || !active.connected || active.microphoneSender == null) return;
        if (audioManager != null && audioManager.isMicrophoneMute()) {
            listener.microphoneError(false);
            return;
        }

        int generation = ++captureGeneration;
        microphone = Microphone.STARTING;
        pendingCapture.set(generation);
        updateAudio();
        try {
            if (active.microphoneTrack == null) {
                active.microphoneSource = factory.createAudioSource(new MediaConstraints());
                active.microphoneTrack = factory.createAudioTrack("p4-phone-mic", active.microphoneSource);
                active.microphoneTrack.setEnabled(false);
                if (!active.microphoneSender.setTrack(active.microphoneTrack, false)) {
                    failMicrophone(true);
                    return;
                }
            }
            // Only the peer controls the recorder. Its native stop joins the capture
            // thread before clearing the JNI buffer. Keep the sender attached across mutes.
            enablePhysicalCapture.set(true);
            active.peer.setAudioRecording(true);
            active.microphoneTrack.setEnabled(true);
            main.postDelayed(microphoneTimeout, MIC_START_TIMEOUT);
        } catch (RuntimeException error) {
            Log.w("P4Rtc", "Could not start microphone", error);
            failMicrophone(true);
        }
    }

    private void stopMicrophone() {
        if (!isMicrophoneRequested()) return;
        microphone = Microphone.OFF;
        enablePhysicalCapture.set(false);
        pendingCapture.set(0);
        captureGeneration++;
        main.removeCallbacks(microphoneTimeout);
        if (call.microphoneTrack != null) call.microphoneTrack.setEnabled(false);
        call.peer.setAudioRecording(false);
        audioDevice.setAudioRecordEnabled(false);
    }

    private long onAudioBuffer(int bytes, int capacity, long timestamp) {
        if (bytes == 0) {
            // Promote the already-running virtual input on its own capture thread.
            // This keeps the JNI buffer registered and avoids a second recorder owner.
            if (enablePhysicalCapture.getAndSet(false)) audioDevice.setAudioRecordEnabled(true);
            else LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        } else if (bytes == capacity) {
            confirmAudioCapture();
        }
        return timestamp;
    }

    private void confirmAudioCapture() {
        if (pendingCapture.get() == 0) return;
        int generation = pendingCapture.getAndSet(0);
        main.post(() -> {
            if (generation != captureGeneration || microphone != Microphone.STARTING) return;
            main.removeCallbacks(microphoneTimeout);
            microphone = Microphone.ON;
            updateAudio();
        });
    }

    private void reportMicError(String error) {
        int generation = captureGeneration;
        Log.w("P4Rtc", "Microphone: " + error);
        main.post(() -> {
            if (generation == captureGeneration && isMicrophoneRequested()) failMicrophone(true);
        });
    }

    private void failMicrophone(boolean resetAudio) {
        if (!isMicrophoneRequested()) return;
        setMicrophone(false);
        listener.microphoneError(resetAudio);
    }

    private void updateAudio() {
        Call active = call;
        boolean connected = active != null && active.connected;
        boolean play = foreground && connected && speaker;
        if (isMicrophoneRequested() || play) routeAudio(true);
        if (active != null && active.peer != null) active.peer.setAudioPlayout(play);
        if (!isMicrophoneRequested() && !play) routeAudio(false);
        listener.audio(connected && active.microphoneSender != null, speaker, microphone);
    }

    @SuppressWarnings("deprecation")
    private void routeAudio(boolean enable) {
        if (audioManager == null || routedAudio == enable) return;
        routedAudio = enable;
        if (enable) {
            previousAudioMode = audioManager.getMode();
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= 31) {
                List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
                boolean external = devices.stream().anyMatch(device -> device.getType() != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                        && device.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
                if (!external) devices.stream().filter(device -> device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                        .findFirst().ifPresent(audioManager::setCommunicationDevice);
            } else {
                previousSpeakerphone = audioManager.isSpeakerphoneOn();
                if (!audioManager.isWiredHeadsetOn() && !audioManager.isBluetoothScoOn()) audioManager.setSpeakerphoneOn(true);
            }
        } else {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice();
            else audioManager.setSpeakerphoneOn(previousSpeakerphone);
            audioManager.setMode(previousAudioMode);
        }
    }

    private boolean send(JSONObject message) {
        return socketOpen && socket != null && socket.send(message.toString());
    }

    private static JSONObject object(String typeKey, String type, String idKey, String id, Object... extra) {
        JSONObject result = new JSONObject();
        try {
            result.put(typeKey, type).put(idKey, id);
            for (int i = 0; i < extra.length; i += 2) result.put((String) extra[i], extra[i + 1]);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return result;
    }

    private void closeCall() {
        Call previous = call;
        if (previous != null) {
            neutral(previous);
            stopMicrophone();
            call = null;
            main.removeCallbacks(previous.watchdog);
            main.removeCallbacks(previous.iceDeadline);
            main.removeCallbacks(previous.headlightTimeout);
            if (previous.receivedVideo != null) previous.receivedVideo.removeSink(video);
            if (previous.peer != null) previous.peer.dispose();
            if (previous.microphoneTrack != null) previous.microphoneTrack.dispose();
            if (previous.microphoneSource != null) previous.microphoneSource.dispose();
        }
        video.clear();
        listener.steering(false);
        updateLights();
        updateAudio();
    }

    private void disconnect() {
        main.removeCallbacks(connectAgain);
        cancelSessionRetry();
        closeCall();
        WebSocket previous = socket;
        profile = null;
        socket = null;
        socketOpen = false;
        if (previous != null) previous.close(1000, null);
    }

    void release() {
        disconnect();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
        factory.dispose();
        audioDevice.release();
        video.release();
        egl.release();
    }

    private final class Call {
        final String id;
        final boolean direct;
        final long startedAt = SystemClock.elapsedRealtime();
        final List<IceCandidate> remoteCandidates = new ArrayList<>();
        final Runnable watchdog = () -> restart(this, true);
        final Runnable iceDeadline = () -> sendAnswer(this);
        final Runnable headlightTimeout = () -> {
            headlightPending = false;
            if (call == this) updateLights();
        };
        PeerConnection peer;
        VideoTrack receivedVideo;
        RtpSender microphoneSender;
        AudioSource microphoneSource;
        AudioTrack microphoneTrack;
        String route;
        boolean answering, remoteSet, localSet, graceScheduled, answerSent, connected, steering;
        boolean lights, headlight, headlightPending;

        Call(String id, boolean direct) { this.id = id; this.direct = direct; }
    }

    private class AnswerObserver implements SdpObserver {
        final Call active;
        AnswerObserver(Call active) { this.active = active; }
        @Override public void onCreateSuccess(SessionDescription description) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) { negotiationFailed(error); }
        @Override public void onSetFailure(String error) { negotiationFailed(error); }

        private void negotiationFailed(String error) {
            Log.w("P4Rtc", "SDP negotiation failed: " + error);
            main.post(() -> restart(active, true));
        }
    }

    private final class PeerEvents implements PeerConnection.Observer {
        final Call active;
        PeerEvents(Call active) { this.active = active; }
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            main.post(() -> peerState(active, state));
        }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { main.post(() -> gather(active)); }
        @Override public void onIceCandidate(IceCandidate candidate) { main.post(() -> gather(active)); }
        @Override public void onTrack(RtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().track();
            main.post(() -> receiveVideo(active, track));
        }
        @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel channel) {}
        @Override public void onRenegotiationNeeded() {}
    }
}
