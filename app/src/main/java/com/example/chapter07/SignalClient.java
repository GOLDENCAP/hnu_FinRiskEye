package com.example.chapter07;  // 声明类所在的包名

// ========== Android 音视频相关 ==========
import android.content.Context;               // Android 上下文（本类通过构造传入）
import android.util.Log;                     // 日志工具

// ========== OpenGL / EGL 相关 ==========
import org.json.JSONException;               // JSON 异常（本类未实际使用）
import org.json.JSONObject;                  // JSON 对象（本类未实际使用）

import java.net.URISyntaxException;          // URI 异常（本类未实际使用）
import java.security.KeyStore;               // 密钥库（本类未实际使用）
import java.security.cert.Certificate;       // 证书（本类未实际使用）
import java.security.cert.CertificateFactory; // 证书工厂（本类未实际使用）
import java.util.concurrent.TimeUnit;         // 时间单位（本类未实际使用）

import javax.net.ssl.SSLContext;              // SSL（本类未实际使用）
import javax.net.ssl.TrustManagerFactory;    // 信任管理器工厂（本类未实际使用）
import javax.net.ssl.X509TrustManager;       // X509 信任管理器（本类未实际使用）

// ========== Socket.IO / OkHttp（本类未实际使用） ==========
import io.socket.client.IO;                  // Socket.IO（本类未实际使用）
import io.socket.client.Socket;              // Socket.IO Socket（本类未实际使用）
import io.socket.emitter.Emitter;            // 事件监听器（本类未实际使用）
import okhttp3.OkHttpClient;                 // OkHttp（本类未实际使用）

/**
 * SignalClient：WebRTC 信令客户端。
 *
 * 职责：
 *   1. 通过 Socket.IO 连接到信令服务器（支持 HTTPS/WSS 自签名证书）
 *   2. 加入指定房间，并广播 SDP / ICE Candidate 等信令消息
 *   3. 通过 OnSignalEventListener 回调上层各种房间事件（用户加入/离开、消息等）
 *
 * 单例模式：全应用只持有一个客户端实例。
 */
public class SignalClient {
    private static final String TAG = "SignalClient";  // 日志 TAG
    private static SignalClient mInstance;              // 静态单例引用
    private OnSignalEventListener mOnSignalEventListener; // 事件回调
    private Context mContext;                            // 用于加载证书的上下文

    private Socket mSocket;   // Socket.IO Socket 实例
    private String mRoomName; // 当前加入的房间名

    /**
     * 信令事件监听器接口，由 Activity 实现以接收各类事件通知。
     */
    public interface OnSignalEventListener {
        void onConnected();                              // 连接成功
        void onConnecting();                             // 正在连接
        void onDisconnected();                           // 连接断开
        void onUserJoined(String roomName, String userID); // 自己加入房间成功
        void onUserLeaved(String roomName, String userID); // 自己离开房间
        void onRemoteUserJoined(String roomName);          // 远端用户加入
        void onRemoteUserLeaved(String roomName, String userID); // 远端用户离开
        void onRoomFull(String roomName, String userID);  // 房间已满
        void onMessage(JSONObject message);              // 收到普通消息（SDP/candidate）
    }

    /**
     * 双重检查单例：首次访问时创建实例。
     */
    public static SignalClient getInstance() {
        if (mInstance == null) {           // 第一次检查
            mInstance = new SignalClient(); // 创建实例
        }
        return mInstance;                  // 返回实例
    }

    /**
     * 初始化（必须先于 joinRoom 调用，传入 Context 用于加载证书）。
     */
    public void init(Context context) {
        mContext = context;  // 保存上下文引用
    }

    /**
     * 注册事件监听器。
     */
    public void setSignalEventListener(OnSignalEventListener listener) {
        mOnSignalEventListener = listener;
    }

    /**
     * 连接到信令服务器并加入房间。
     * @param url 服务器地址（wss:// 或 https://）
     * @param roomName 房间名
     */
    public void joinRoom(String url, String roomName) {
        mRoomName = roomName;  // 保存房间名

        try {
            // ===== 1. 规范化 URL（去掉末尾斜杠） =====
            String wsUrl = url;
            if (wsUrl.endsWith("/")) {                  // 末尾有 /
                wsUrl = wsUrl.substring(0, wsUrl.length() - 1); // 去掉
            }

            Log.i(TAG, "Connecting to: " + wsUrl);  // 记录连接的 URL

            // ===== 2. 创建 OkHttpClient.Builder =====
            OkHttpClient.Builder okHttpBuilder = new OkHttpClient.Builder();
            okHttpBuilder.connectTimeout(10, TimeUnit.SECONDS); // 连接超时 10 秒
            okHttpBuilder.readTimeout(10, TimeUnit.SECONDS);    // 读超时 10 秒
            okHttpBuilder.writeTimeout(10, TimeUnit.SECONDS);   // 写超时 10 秒

            // ========== 添加 SSL 证书支持 ==========
            if (url.startsWith("https") && mContext != null) {  // HTTPS 协议且上下文可用
                try {
                    // 加载你的自签名证书
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    java.io.InputStream certInput = mContext.getResources().openRawResource(R.raw.certificate);
                    Certificate ca = cf.generateCertificate(certInput);
                    certInput.close();

                    // 创建 KeyStore 并添加证书
                    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                    keyStore.load(null, null);
                    keyStore.setCertificateEntry("ca", ca);

                    // 创建 TrustManager
                    TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                            TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(keyStore);
                    X509TrustManager trustManager = (X509TrustManager) tmf.getTrustManagers()[0];

                    // 创建 SSLContext
                    SSLContext sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, tmf.getTrustManagers(), null);

                    // 配置到 OkHttpClient
                    okHttpBuilder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
                    okHttpBuilder.hostnameVerifier((hostname, session) -> true); // 信任所有主机名（自签场景）

                    Log.i(TAG, "SSL Certificate loaded successfully");

                } catch (Exception e) {
                    Log.e(TAG, "Failed to load SSL certificate: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            // =====================================

            // 创建 OkHttpClient
            OkHttpClient okHttpClient = okHttpBuilder.build();

            // ===== 3. 配置 Socket.IO Options =====
            IO.Options options = new IO.Options();
            options.transports = new String[]{"websocket"};  // 只用 WebSocket 传输
            options.reconnection = true;                     // 允许自动重连
            options.callFactory = okHttpClient;               // HTTP 调用工厂
            options.webSocketFactory = okHttpClient;          // WebSocket 工厂

            // ===== 4. 创建 Socket 并连接 =====
            mSocket = IO.socket(wsUrl, options);             // 创建 Socket 实例

            // ===== 5. 注册内置事件监听 =====
            mSocket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {  // 连接成功事件
                @Override
                public void call(Object... args) {
                    Log.i(TAG, "Socket.IO connected");
                    if (mOnSignalEventListener != null) {
                        mOnSignalEventListener.onConnected();    // 回调上层
                    }
                    // 连接成功后加入房间
                    mSocket.emit("join", mRoomName);             // 发送 join 事件
                }
            }).on(Socket.EVENT_DISCONNECT, new Emitter.Listener() { // 断开事件
                @Override
                public void call(Object... args) {
                    Log.i(TAG, "Socket.IO disconnected");
                    if (mOnSignalEventListener != null) {
                        mOnSignalEventListener.onDisconnected();
                    }
                }
            }).on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() { // 连接错误事件
                @Override
                public void call(Object... args) {
                    Log.e(TAG, "Connection error: " + args[0]);
                    if (args.length > 1) {
                        Log.e(TAG, "Error details: " + args[1]);
                    }
                }
            });

            // ===== 6. 注册自定义业务事件 =====
            // "joined" 事件：服务器确认我已加入房间
            mSocket.on("joined", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    String room = (String) args[0];
                    String userId = (String) args[1];
                    Log.i(TAG, "Joined room: " + room + ", userId: " + userId);
                    if (mOnSignalEventListener != null) {
                        mOnSignalEventListener.onUserJoined(room, userId);
                    }
                }
            });

            // "otherjoin" 事件：其他人加入了房间
            mSocket.on("otherjoin", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    String room = (String) args[0];
                    String userId = (String) args[1];
                    Log.i(TAG, "Remote user joined: " + userId);
                    if (mOnSignalEventListener != null) {
                        mOnSignalEventListener.onRemoteUserJoined(room);
                    }
                }
            });

            // "bye" 事件：其他人离开了房间
            mSocket.on("bye", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    String room = (String) args[0];
                    String userId = (String) args[1];
                    Log.i(TAG, "Remote user left: " + userId);
                    if (mOnSignalEventListener != null) {
                        mOnSignalEventListener.onRemoteUserLeaved(room, userId);
                    }
                }
            });

            // "leaved" 事件：服务器确认我已离开
            mSocket.on("leaved", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    String room = (String) args[0];
                    String userId = (String) args[1];
                    Log.i(TAG, "Left room: " + room);
                    if (mOnSignalEventListener != null) {
                        mOnSignalEventListener.onUserLeaved(room, userId);
                    }
                }
            });

            // "full" 事件：房间已满
            mSocket.on("full", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    String room = (String) args[0];
                    String userId = (String) args[1];
                    Log.i(TAG, "Room is full");
                    if (mOnSignalEventListener != null) {
                        mOnSignalEventListener.onRoomFull(room, userId);
                    }
                }
            });

            // "message" 事件：收到 SDP / candidate 等信令消息
            mSocket.on("message", new Emitter.Listener() {
                @Override
                public void call(Object... args) {
                    String room = (String) args[0];
                    JSONObject data = (JSONObject) args[1];
                    Log.i(TAG, "Received message: " + data);
                    if (mOnSignalEventListener != null) {
                        mOnSignalEventListener.onMessage(data);
                    }
                }
            });

            // ===== 7. 发起连接 =====
            mSocket.connect();

        } catch (URISyntaxException e) {  // URL 格式错误
            Log.e(TAG, "URI error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 向服务器发送一条 SDP / ICE 等信令消息。
     * @param message 要发送的 JSON 对象（包含 type/sdp 或 type/candidate 等字段）
     */
    public void sendMessage(JSONObject message) {
        if (mSocket != null && mSocket.connected()) {  // Socket 存在且已连接
            Log.i(TAG, "Sending message: " + message);
            mSocket.emit("message", mRoomName, message);  // 向房间广播消息
        } else {
            Log.e(TAG, "Socket not connected");  // 未连接
        }
    }

    /**
     * 离开房间并断开 Socket。
     */
    public void leaveRoom() {
        if (mSocket != null) {
            mSocket.emit("leave", mRoomName);  // 通知服务器离开
            mSocket.disconnect();              // 断开连接
        }
    }
}