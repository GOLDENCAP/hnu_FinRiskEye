package com.example.chapter07.my_audio_record_0829;

import android.content.Context;
import android.media.MediaRecorder;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class audio_record_interface_encapsulation {

    final static int SAMPLE_RATE = 48000;
    private static final String TAG = "AudioRecord";
    private static boolean factoryInitialized = false;

    private JavaAudioDeviceModule.SamplesReadyCallback samplesReadyCallback;
    private JavaAudioDeviceModule adm;
    private Context appContext;
    private PeerConnectionFactory factory;
    private PeerConnection pcSend;
    private PeerConnection pcRecv;
    private AudioSource audioSource;
    private AudioTrack audioTrack;

    private final List<IceCandidate> pendingToRecv = new ArrayList<>();
    private final List<IceCandidate> pendingToSend = new ArrayList<>();
    private boolean recvRemoteReady = false;
    private boolean sendRemoteReady = false;

    private volatile boolean isRecording = false;
    private FileOutputStream pcmOutputStream;
    private File pcmFile;
    private int logCounter = 0;
    private long totalBytesWritten = 0;

    public audio_record_interface_encapsulation() {
        samplesReadyCallback = new JavaAudioDeviceModule.SamplesReadyCallback() {
            @Override
            public void onWebRtcAudioRecordSamplesReady(JavaAudioDeviceModule.AudioSamples audioSamples) {
                if (!isRecording) return;

                byte[] data = audioSamples.getData();
                int sampleRate = audioSamples.getSampleRate();

                if (pcmOutputStream != null && data != null && data.length > 0) {
                    try {
                        pcmOutputStream.write(data);
                        totalBytesWritten += data.length;
                        logEvery5Seconds(data.length, sampleRate);
                    } catch (IOException e) {
                        Log.e(TAG, "写入失败", e);
                    }
                }
            }
        };
    }

    public void create_audio_builder(Context context) {
        this.appContext = context.getApplicationContext();

        if (!factoryInitialized) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                            .createInitializationOptions()
            );
            factoryInitialized = true;
            Log.d(TAG, "PeerConnectionFactory 全局初始化完成");
        }

        adm = JavaAudioDeviceModule.builder(appContext)
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setInputSampleRate(SAMPLE_RATE)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setSamplesReadyCallback(samplesReadyCallback)
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    @Override
                    public void onWebRtcAudioRecordInitError(String errorMessage) {
                        Log.e(TAG, "AudioRecord init error: " + errorMessage);
                    }

                    @Override
                    public void onWebRtcAudioRecordStartError(
                            JavaAudioDeviceModule.AudioRecordStartErrorCode errorCode,
                            String errorMessage) {
                        Log.e(TAG, "AudioRecord start error: " + errorCode + " " + errorMessage);
                    }

                    @Override
                    public void onWebRtcAudioRecordError(String errorMessage) {
                        Log.e(TAG, "AudioRecord error: " + errorMessage);
                    }
                })
                .createAudioDeviceModule();

        factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory();

        audioSource = factory.createAudioSource(new MediaConstraints());
        audioTrack = factory.createAudioTrack("local_rec", audioSource);
        audioTrack.setEnabled(true);

        startLocalCapturePipeline();

        Log.d(TAG, "WebRTC 音频引擎已启动，正在建立本机 loopback");
    }

    /**
     * WebRTC 只有在发送流真正跑起来后才会开麦。
     * 没有对端时 setLocalDescription 不够，这里用两个 PeerConnection 本机互连。
     */
    private void startLocalCapturePipeline() {
        PeerConnection.RTCConfiguration rtcConfig = newRtcConfig();

        pcSend = factory.createPeerConnection(rtcConfig, new PcObserver("send") {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                addIceToRecv(iceCandidate);
            }
        });
        pcRecv = factory.createPeerConnection(rtcConfig, new PcObserver("recv") {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                addIceToSend(iceCandidate);
            }
        });

        if (pcSend == null || pcRecv == null) {
            Log.e(TAG, "createPeerConnection 失败，麦克风采集不会启动");
            return;
        }

        pcRecv.setAudioPlayout(false);
        pcSend.addTrack(audioTrack, Collections.singletonList("ARDAMS"));

        MediaConstraints offerConstraints = new MediaConstraints();
        offerConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        offerConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        pcSend.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                Log.d(TAG, "createOffer 成功");
                pcSend.setLocalDescription(new LogSdp("send setLocal") {
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "send setLocalDescription 成功");
                        pcRecv.setRemoteDescription(new LogSdp("recv setRemote") {
                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "recv setRemoteDescription 成功");
                                markRecvRemoteReady();
                                createAnswerOnRecv();
                            }
                        }, offer);
                    }
                }, offer);
            }

            @Override
            public void onSetSuccess() {
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createOffer 失败: " + s);
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "createOffer/set 失败: " + s);
            }
        }, offerConstraints);
    }

    private void createAnswerOnRecv() {
        MediaConstraints answerConstraints = new MediaConstraints();
        answerConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        answerConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        pcRecv.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription answer) {
                Log.d(TAG, "createAnswer 成功");
                pcRecv.setLocalDescription(new LogSdp("recv setLocal") {
                    @Override
                    public void onSetSuccess() {
                        Log.d(TAG, "recv setLocalDescription 成功");
                        pcSend.setRemoteDescription(new LogSdp("send setRemote") {
                            @Override
                            public void onSetSuccess() {
                                Log.d(TAG, "send setRemoteDescription 成功，loopback 已建立");
                                markSendRemoteReady();
                            }
                        }, answer);
                    }
                }, answer);
            }

            @Override
            public void onSetSuccess() {
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "createAnswer 失败: " + s);
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "createAnswer/set 失败: " + s);
            }
        }, answerConstraints);
    }

    private synchronized void addIceToRecv(IceCandidate candidate) {
        if (pcRecv == null) return;
        if (recvRemoteReady) {
            pcRecv.addIceCandidate(candidate);
        } else {
            pendingToRecv.add(candidate);
        }
    }

    private synchronized void addIceToSend(IceCandidate candidate) {
        if (pcSend == null) return;
        if (sendRemoteReady) {
            pcSend.addIceCandidate(candidate);
        } else {
            pendingToSend.add(candidate);
        }
    }

    private synchronized void markRecvRemoteReady() {
        recvRemoteReady = true;
        for (IceCandidate c : pendingToRecv) {
            pcRecv.addIceCandidate(c);
        }
        pendingToRecv.clear();
    }

    private synchronized void markSendRemoteReady() {
        sendRemoteReady = true;
        for (IceCandidate c : pendingToSend) {
            pcSend.addIceCandidate(c);
        }
        pendingToSend.clear();
    }

    private static PeerConnection.RTCConfiguration newRtcConfig() {
        PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(new ArrayList<>());
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        return rtcConfig;
    }

    public void startRecording(String filePath) {
        try {
            pcmFile = new File(filePath);
            if (pcmFile.getParentFile() != null) {
                pcmFile.getParentFile().mkdirs();
            }
            pcmOutputStream = new FileOutputStream(pcmFile);
            logCounter = 0;
            totalBytesWritten = 0;
            isRecording = true;
            Log.d(TAG, "开始录音，保存到: " + filePath);
        } catch (IOException e) {
            Log.e(TAG, "创建文件失败", e);
        }
    }

    public void stopRecording() {
        isRecording = false;
        if (pcmOutputStream != null) {
            try {
                pcmOutputStream.flush();
                pcmOutputStream.close();
                long fileSize = pcmFile != null ? pcmFile.length() : 0;
                Log.d(TAG, "录音停止，已写入 " + totalBytesWritten + " 字节, 文件大小: "
                        + fileSize + " bytes, 路径: "
                        + (pcmFile != null ? pcmFile.getAbsolutePath() : "null"));
            } catch (IOException e) {
                Log.e(TAG, "关闭文件失败", e);
            }
            pcmOutputStream = null;
        }
    }

    public void release() {
        stopRecording();
        if (pcSend != null) {
            pcSend.close();
            pcSend.dispose();
            pcSend = null;
        }
        if (pcRecv != null) {
            pcRecv.close();
            pcRecv.dispose();
            pcRecv = null;
        }
        if (audioTrack != null) {
            audioTrack.dispose();
            audioTrack = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        if (adm != null) {
            adm.release();
            adm = null;
        }
    }

    private void logEvery5Seconds(int bytes, int sr) {
        if (logCounter == 0) {
            Log.d(TAG, "收到第一帧 PCM: " + bytes + " 字节, 采样率: " + sr + " Hz");
        }
        logCounter++;
        if (logCounter % 250 == 0) {
            Log.d(TAG, "已录制: " + bytes + " 字节/帧, 采样率: " + sr
                    + " Hz, 累计约 " + (logCounter / 50) + " 秒, 总字节: " + totalBytesWritten);
        }
    }

    private static class LogSdp implements SdpObserver {
        private final String label;

        LogSdp(String label) {
            this.label = label;
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, label + " create 失败: " + s);
        }

        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, label + " set 失败: " + s);
        }
    }

    private static class PcObserver implements PeerConnection.Observer {
        private final String name;

        PcObserver(String name) {
            this.name = name;
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, name + " ICE: " + iceConnectionState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(TAG, name + " ICE gathering: " + iceGatheringState);
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
        }

        @Override
        public void onRenegotiationNeeded() {
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        }
    }
}
