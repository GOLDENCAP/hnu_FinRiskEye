# 从零实现 SignalClient —— 渐进式构建指南

> **目标读者**：已经会基本 Android 开发、知道 WebRTC 需要"信令服务器"传输 SDP / ICE candidate，但对 Socket.IO、SSL 证书、单例模式 + 事件回调链不熟的人。
>
> **本文目标**：把当前 `app/src/main/java/com/example/chapter07/SignalClient.java`（268 行）从"空类"逐步演进到当前规模。每一阶段只做一件事，做完之后你能运行、能调试、能验证；再进入下一阶段。
>
> **阅读建议**：先按顺序读一遍，再真正动手写时回到对应阶段复制代码。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [前置知识速查](#2-前置知识速查)
3. [阶段 1 —— 接口先行：先定义 OnSignalEventListener](#3-阶段-1--接口先行先定义-onsignaleventlistener)
4. [阶段 2 —— 单例骨架 + 字段占位](#4-阶段-2--单例骨架--字段占位)
5. [阶段 3 —— 加 init() 和 setSignalEventListener()](#5-阶段-3--加-init-和-setsignaleventlistener)
6. [阶段 4 —— 第一次连接 Socket.IO（最简版本）](#6-阶段-4--第一次连接-socketio最简版本)
7. [阶段 5 —— 注册 EVENT_CONNECT + 回调 onConnected](#7-阶段-5--注册-event_connect--回调-onconnected)
8. [阶段 6 —— 注册 EVENT_DISCONNECT / CONNECT_ERROR](#8-阶段-6--注册-event_disconnect--connect_error)
9. [阶段 7 —— 加 sendMessage()](#9-阶段-7--加-sendmessage)
10. [阶段 8 —— 加 leaveRoom()](#10-阶段-8--加-leaveroom)
11. [阶段 9 —— 加自定义 "joined" / "otherjoin" 事件](#11-阶段-9--加自定义-joined--otherjoin-事件)
12. [阶段 10 —— 加 "bye" / "leaved" / "full" 事件](#12-阶段-10--加-bye--leaved--full-事件)
13. [阶段 11 —— 加 "message" 事件（SDP / candidate 通道）](#13-阶段-11--加-message-事件sdp--candidate-通道)
14. [阶段 12 —— URL 规范化（去末尾斜杠）](#14-阶段-12--url-规范化去末尾斜杠)
15. [阶段 13 —— OkHttpClient + 超时配置](#15-阶段-13--okhttpclient--超时配置)
16. [阶段 14 —— HTTPS / WSS 自签名证书支持](#16-阶段-14--https--wss-自签名证书支持)
17. [阶段 15 —— 配置 IO.Options（transport / reconnection / factory）](#17-阶段-15--配置-iooptionstransport--reconnection--factory)
18. [完整最终代码对照](#18-完整最终代码对照)
19. [演进回顾表](#19-演进回顾表)
20. [常见报错对照表](#20-常见报错对照表)

---

## 1. 这个类到底要做什么

一句话：**通过 Socket.IO 连接到信令服务器，加入房间，把房间里的 SDP / ICE candidate 透传给上层。**

为什么需要这个？因为 WebRTC 的两端（浏览器/手机）建立连接前，必须通过"信令服务器"交换：
- SDP Offer / Answer（双方的能力描述）
- ICE Candidate（双方的网络地址候选）

这个类一共有 6 个核心职责：

1. **单例**：全应用只持有一个 SignalClient（避免多连接浪费）
2. **连接**：用 Socket.IO + WebSocket 连接到服务器（支持 HTTPS/WSS 自签名）
3. **房间管理**：加入 / 离开指定房间
4. **消息透传**：上层发 SDP / candidate → SignalClient → 服务器 → 另一个客户端
5. **事件回调**：服务器推 "用户加入 / 离开 / 消息" 时通知上层
6. **SSL 兜底**：自签名证书场景下，握手不能卡住

后面 15 个阶段都在解决"这 6 件事怎么一件件做对"。

---

## 2. 前置知识速查

### 2.1 WebRTC 信令流程图

```
[客户端 A]                        [信令服务器]                        [客户端 B]
   │                                    │                                  │
   │──── join(room) ────────────────────>                                  │
   │                                    │◄──────── join(room) ────────────│
   │<─────── otherjoin ─────────────────│                                  │
   │                                    │                                  │
   │──── message({type:offer, sdp}) ──>│                                  │
   │                                    │──── message({type:offer, sdp}) ─>│
   │                                    │                                  │
   │                                    │<─── message({type:answer, sdp}) ─│
   │<─── message({type:answer, sdp}) ──│                                  │
   │                                    │                                  │
   │──── message({type:candidate, ...})>│                                  │
   │                                    │──── message({type:candidate,...})>│
   │  ...两端 P2P 建连完成...           │                                  │
   │                                    │                                  │
   │──── leave(room) ──────────────────>│                                  │
```

### 2.2 Socket.IO 事件 vs 自定义事件

| 事件名 | 类型 | 说明 |
|--------|------|------|
| `EVENT_CONNECT` | 内置 | Socket.IO 连接成功 |
| `EVENT_DISCONNECT` | 内置 | 连接断开 |
| `EVENT_CONNECT_ERROR` | 内置 | 连接失败 |
| `joined` | 自定义 | 服务器通知"我加入房间" |
| `otherjoin` | 自定义 | 服务器通知"别人加入房间" |
| `bye` | 自定义 | 服务器通知"别人离开房间" |
| `leaved` | 自定义 | 服务器通知"我已离开房间" |
| `full` | 自定义 | 房间已满 |
| `message` | 自定义 | SDP / candidate 透传通道 |

### 2.3 单例模式要点

```java
public static SignalClient getInstance() {
    if (mInstance == null) {           // 第一次检查（无锁）
        mInstance = new SignalClient();
    }
    return mInstance;
}
```

- 当前实现**没有用 synchronized**，所以严格说不是"线程安全的单例"。
- 在 Android 上 SignalClient 通常只在 UI 线程访问，单线程场景下不会出问题。
- 真正的线程安全写法见附录。

### 2.4 Socket.IO 的 IO.Options 关键字段

| 字段 | 作用 | 当前值 |
|------|------|--------|
| `transports` | 传输方式 | `["websocket"]`（只用 WS，不用 polling） |
| `reconnection` | 是否自动重连 | `true` |
| `callFactory` | HTTP 调用工厂 | OkHttpClient（用于 polling fallback） |
| `webSocketFactory` | WebSocket 工厂 | OkHttpClient（用于 WS 握手） |

### 2.5 WSS 自签名证书的信任链

```
应用内置 R.raw.certificate (PEM 格式)
        │
        ▼
CertificateFactory.generateCertificate(InputStream)
        │
        ▼
KeyStore.setCertificateEntry("ca", cert)
        │
        ▼
TrustManagerFactory.init(keyStore)
        │
        ▼
SSLContext.init(null, tmf.getTrustManagers(), null)
        │
        ▼
OkHttpClient.sslSocketFactory(sslContext.getSocketFactory(), tm)
        │
        ▼
Socket.IO 用这个 OkHttpClient 握手 WSS
```

---

## 3. 阶段 1 —— 接口先行：先定义 OnSignalEventListener

### 3.1 本阶段目标
先把"上层需要监听哪些事件"列出来，写成接口。

### 3.2 遇到的新问题
**问题：接口为什么先写？**
- 答：因为这个类是被 Activity / Fragment 调用的。先把回调接口定下来，调用方就能写 mock 实现进行联调了。
- 如果先写 SignalClient 内部，调用方不知道该期待什么回调。

**问题：先写哪几个回调？**
- 答：写**当前所有**回调（一次性写完 9 个方法），因为：
  - 接口定了就不会再改
  - 调用方一次实现所有方法比"实现 1 个 → 加 1 个 → 再实现"更简单

### 3.3 完整代码

```java
package com.example.chapter07;

import org.json.JSONObject;

public class SignalClient {

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
}
```

### 3.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `OnSignalEventListener` 接口 | 上层订阅 SignalClient 事件的统一入口 |
| 9 个回调方法 | 覆盖所有业务事件（连接 / 房间 / 消息） |

### 3.5 阶段 1 后能干嘛
- 调用方可以先 mock 一个实现，比如：
  ```java
  signalClient.setSignalEventListener(new SignalClient.OnSignalEventListener() {
      @Override public void onConnected() { /* TODO */ }
      // ... 其它 8 个方法
  });
  ```
- 编译通过

### 3.6 经验教训
**接口里 `onConnecting()` 当前没人调用**：因为 Socket.IO 的连接是同步触发的，没有"连接中"中间态。但保留这个回调便于以后扩展（比如改成异步连接时用到）。写当前用不到的回调是合理的。

---

## 4. 阶段 2 —— 单例骨架 + 字段占位

### 4.1 本阶段目标
把单例 + 字段 + 构造函数加好，但都还是占位。

### 4.2 遇到的新问题
**问题 1：为什么用单例？**
- 答：
  - 一个应用只应该有一个 WebRTC 信令连接
  - 多个 Activity / Fragment 都要用同一个连接
  - 单例让"获取实例"变成 `SignalClient.getInstance()` 一行

**问题 2：构造要私有吗？**
- 答：要。`private SignalClient()` 防止外部 `new` 出第二个实例。

**问题 3：现在该有哪些字段？**
- 答：根据"这个类要做的事"，需要的字段是：
  - `mOnSignalEventListener`：回调
  - `mContext`：加载 SSL 证书用
  - `mSocket`：Socket.IO 连接
  - `mRoomName`：当前加入的房间

### 4.3 完整代码

```java
package com.example.chapter07;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import io.socket.client.Socket;

public class SignalClient {

    private static final String TAG = "SignalClient";

    private static SignalClient mInstance;
    private OnSignalEventListener mOnSignalEventListener;
    private Context mContext;
    private Socket mSocket;
    private String mRoomName;

    public interface OnSignalEventListener {
        void onConnected();
        void onConnecting();
        void onDisconnected();
        void onUserJoined(String roomName, String userID);
        void onUserLeaved(String roomName, String userID);
        void onRemoteUserJoined(String roomName);
        void onRemoteUserLeaved(String roomName, String userID);
        void onRoomFull(String roomName, String userID);
        void onMessage(JSONObject message);
    }

    private SignalClient() {
        // 私有构造，禁止外部 new
    }

    public static SignalClient getInstance() {
        if (mInstance == null) {
            mInstance = new SignalClient();
        }
        return mInstance;
    }
}
```

### 4.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `private static SignalClient mInstance` | 单例引用 |
| `private SignalClient()` 私有构造 | 防止外部 new |
| `getInstance()` | 唯一获取入口 |
| `mOnSignalEventListener` | 回调引用 |
| `mContext` | SSL 证书加载用 |
| `mSocket` / `mRoomName` | 连接状态 |

### 4.5 阶段 2 后能干嘛
- `SignalClient.getInstance()` 能拿到实例
- 调用方可以 setSignalEventListener（虽然还没有这个方法）

---

## 5. 阶段 3 —— 加 init() 和 setSignalEventListener()

### 5.1 本阶段目标
让调用方能注入 Context 和 Listener。

### 5.2 遇到的新问题
**问题 1：Context 什么时候传？**
- 答：必须在 `joinRoom` 之前传。因为 joinRoom 时可能要读 `R.raw.certificate`，没 Context 就读不到。
- 把 init 做成独立方法，调用方按顺序：`getInstance() → init(ctx) → setListener(...) → joinRoom(...)`。

**问题 2：setSignalEventListener 支持多次调用吗？**
- 答：支持。后调用的覆盖前面的（接口回调通常是"最后一次有效"）。

### 5.3 完整代码（增量）

```java
/**
 * 初始化（必须先于 joinRoom 调用，传入 Context 用于加载证书）。
 */
public void init(Context context) {
    mContext = context;
}

/**
 * 注册事件监听器。
 */
public void setSignalEventListener(OnSignalEventListener listener) {
    mOnSignalEventListener = listener;
}
```

### 5.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `init(Context)` | 注入 Context，用于 SSL 证书加载 |
| `setSignalEventListener(...)` | 注册回调 |

### 5.5 阶段 3 后能干嘛
- 调用方能完整地把"依赖"全部注入完，准备连接

---

## 6. 阶段 6 —— 第一次连接 Socket.IO（最简版本）

> 注：跳到阶段 6 是因为阶段 4、5 的"事件注册"会在阶段 7、8 才完整加。先把连接跑通。

### 6.1 本阶段目标
让 `joinRoom(url, roomName)` 真正连接到 Socket.IO 服务器（不带 SSL、不带重连配置）。

### 6.2 遇到的新问题
**问题 1：怎么创建 Socket？**
- 答：`IO.socket(url, options)`。`IO` 是 Socket.IO 的入口类。

**问题 2：URL 末尾的 "/" 要去掉吗？**
- 答：要。`https://server.com/` 和 `https://server.com` 在 Socket.IO 里行为不一样，统一去末尾 `/`。

**问题 3：URISyntaxException 从哪儿抛？**
- 答：`IO.socket(url, options)` 在 URL 格式非法时抛。

### 6.3 完整代码（到阶段 6 为止）

```java
package com.example.chapter07;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;

public class SignalClient {

    private static final String TAG = "SignalClient";

    private static SignalClient mInstance;
    private OnSignalEventListener mOnSignalEventListener;
    private Context mContext;
    private Socket mSocket;
    private String mRoomName;

    public interface OnSignalEventListener {
        void onConnected();
        void onConnecting();
        void onDisconnected();
        void onUserJoined(String roomName, String userID);
        void onUserLeaved(String roomName, String userID);
        void onRemoteUserJoined(String roomName);
        void onRemoteUserLeaved(String roomName, String userID);
        void onRoomFull(String roomName, String userID);
        void onMessage(JSONObject message);
    }

    private SignalClient() {}

    public static SignalClient getInstance() {
        if (mInstance == null) {
            mInstance = new SignalClient();
        }
        return mInstance;
    }

    public void init(Context context) {
        mContext = context;
    }

    public void setSignalEventListener(OnSignalEventListener listener) {
        mOnSignalEventListener = listener;
    }

    public void joinRoom(String url, String roomName) {
        mRoomName = roomName;
        try {
            // ===== 1) 规范化 URL =====
            String wsUrl = url;
            if (wsUrl.endsWith("/")) {
                wsUrl = wsUrl.substring(0, wsUrl.length() - 1);
            }
            Log.i(TAG, "Connecting to: " + wsUrl);

            // ===== 2) 创建 Socket 并连接 =====
            mSocket = IO.socket(wsUrl);
            mSocket.connect();
            Log.i(TAG, "Socket connect() called");

        } catch (URISyntaxException e) {
            Log.e(TAG, "URI error: " + e.getMessage());
        }
    }
}
```

### 6.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `joinRoom(url, roomName)` 方法 | 主入口 |
| `if (wsUrl.endsWith("/"))` | 规范化 URL |
| `IO.socket(wsUrl)` | 创建 Socket 实例 |
| `mSocket.connect()` | 发起连接 |
| `catch (URISyntaxException)` | URL 格式错误的兜底 |

### 6.5 阶段 6 后能干嘛
- 调用 `joinRoom` 后，Socket.IO 会连接到服务器
- logcat 能看到 `Connecting to: ...` 和 `Socket connect() called`
- 但还没有任何事件回调注册，所以 CONNECT / DISCONNECT 都收不到

### 6.6 调试技巧
**如果服务器配对了但还是连不上**，先看 `IO.socket` 是否抛异常：
- `URISyntaxException`：URL 格式错（比如 `wss://` 写成 `ws://`）
- `RuntimeException: xhr poll error`：服务器地址不通（DNS / 防火墙）

---

## 7. 阶段 7 —— 注册 EVENT_CONNECT + 回调 onConnected

### 7.1 本阶段目标
连接成功后回调 `onConnected()`，并自动向服务器发 `join` 事件。

### 7.2 遇到的新问题
**问题 1：Socket.IO 怎么注册事件？**
- 答：`mSocket.on("event_name", new Emitter.Listener() { public void call(Object... args) {...} });`
- `EVENT_CONNECT` 是内置常量，等同于字符串 `"connect"`。

**问题 2：CONNECT 后为什么要立刻 emit "join"？**
- 答：因为加入房间是业务需求。连接成功 → 服务器知道有人连上 → 立刻告诉他"我要加入 XXX 房间"。

**问题 3：emit 第一个参数是事件名，第二个是参数列表吗？**
- 答：是的。`mSocket.emit("join", roomName)` 会向服务器发送 `["join", roomName]` 数组。

### 7.3 完整代码（增量）

```java
// 在 mSocket.connect() 之前加：

mSocket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
    @Override
    public void call(Object... args) {
        Log.i(TAG, "Socket.IO connected");
        if (mOnSignalEventListener != null) {
            mOnSignalEventListener.onConnected();
        }
        mSocket.emit("join", mRoomName);
    }
});

mSocket.connect();
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `mSocket.on(Socket.EVENT_CONNECT, ...)` | 监听连接成功 |
| `onConnected()` 回调 | 通知上层"我连上了" |
| `mSocket.emit("join", mRoomName)` | 告诉服务器加入房间 |

### 7.5 阶段 7 后能干嘛
- 完整跑通"客户端 → 服务器 → 服务器收到 join"链路
- 验证服务器是否真的能识别你的 join 消息

---

## 8. 阶段 8 —— 注册 EVENT_DISCONNECT / CONNECT_ERROR

### 8.1 本阶段目标
让上层知道连接什么时候断开 / 出错。

### 8.2 遇到的新问题
**问题 1：为什么 DISCONNECT 要回调？**
- 答：UI 要根据状态切换按钮文字（"加入房间" → "重新加入"）。

**问题 2：CONNECT_ERROR 要不要回调？**
- 当前实现：**只 log，不回调上层**。因为 `onDisconnected` 和 `onConnectError` 在业务上通常合并处理（都是"未连上"）。
- 未来扩展：可以加 `onConnectError(String reason)` 把 args[0] 传上去。

**问题 3：args[1] 是什么？**
- Socket.IO 在 CONNECT_ERROR 时通常传两个参数：
  - args[0]：异常对象 / 错误描述
  - args[1]：详细描述
- 当前只 log args[0]，打印 args[1] 是为了调试时拿更多上下文。

### 8.3 完整代码（增量）

```java
mSocket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
    @Override
    public void call(Object... args) {
        Log.i(TAG, "Socket.IO disconnected");
        if (mOnSignalEventListener != null) {
            mOnSignalEventListener.onDisconnected();
        }
    }
}).on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
    @Override
    public void call(Object... args) {
        Log.e(TAG, "Connection error: " + args[0]);
        if (args.length > 1) {
            Log.e(TAG, "Error details: " + args[1]);
        }
    }
});
```

### 8.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `EVENT_DISCONNECT` 监听 | 连接断开时回调 |
| `EVENT_CONNECT_ERROR` 监听 | 连接失败时 log（不回调上层） |

### 8.5 阶段 8 后能干嘛
- 网络断开时上层能收到通知
- 调试时能看到 connect error 的具体原因

---

## 9. 阶段 9 —— 加 sendMessage()

### 9.1 本阶段目标
让上层能把 SDP / candidate 发给服务器。

### 9.2 遇到的新问题
**问题 1：emit 的参数顺序要和服务器约定一致**
- 当前约定：`emit("message", roomName, message)` —— 服务器拿到 `["message", "roomName", {sdp:...}]`
- 服务器解析：第一个是事件名，第二个是房间，第三个是消息内容。

**问题 2：发送前要不要判断 connected？**
- 答：要。未连接时 emit 会被 Socket.IO 排队（默认行为），但通常业务上希望"立刻 log 错误"。
- 当前实现：判断 `mSocket != null && mSocket.connected()`，不连则 log error。

### 9.3 完整代码

```java
/**
 * 向服务器发送一条 SDP / ICE 等信令消息。
 */
public void sendMessage(JSONObject message) {
    if (mSocket != null && mSocket.connected()) {
        Log.i(TAG, "Sending message: " + message);
        mSocket.emit("message", mRoomName, message);
    } else {
        Log.e(TAG, "Socket not connected");
    }
}
```

### 9.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `sendMessage(JSONObject)` | 上层发 SDP / candidate 的入口 |
| `connected()` 判断 | 防未连接发送 |
| `emit("message", mRoomName, message)` | 按约定发给服务器 |

### 9.5 阶段 9 后能干嘛
- 上层能把 PeerConnection 的 SDP / candidate 发到服务器

---

## 10. 阶段 10 —— 加 leaveRoom()

### 10.1 本阶段目标
让上层能优雅地断开连接。

### 10.2 遇到的新问题
**问题 1：要先 emit "leave" 还是先 disconnect？**
- 答：先 emit "leave"。让服务器有机会广播 `bye` 给其他人。
- 如果直接 disconnect，服务器不知道你走了，其他人就收不到 `bye`。

**问题 2：要不要把 mSocket 置 null？**
- 当前实现：**不置 null**。因为可能还会 joinRoom 同一个 socket。
- 如果你确信 leaveRoom 后不会再用，应该置 null 让 GC 回收 native 资源。

### 10.3 完整代码

```java
/**
 * 离开房间并断开 Socket。
 */
public void leaveRoom() {
    if (mSocket != null) {
        mSocket.emit("leave", mRoomName);
        mSocket.disconnect();
    }
}
```

### 10.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `leaveRoom()` | 离开房间入口 |
| 先 emit 后 disconnect | 让服务器有机会广播 bye |

### 10.5 阶段 10 后能干嘛
- 完整跑通"加入 → 收发消息 → 离开"链路
- 在网络好的情况下能完整跑通单次会话

---

## 11. 阶段 11 —— 加自定义 "joined" / "otherjoin" 事件

### 11.1 本阶段目标
让上层知道"我加入房间成功"和"别人加入了房间"。

### 11.2 遇到的新问题
**问题 1：服务器发的 "joined" 事件 payload 是什么？**
- 当前约定：服务器发 `["joined", "roomName", "userId"]`
- args[0] = roomName
- args[1] = userId

**问题 2："otherjoin" 为什么不回调 userId？**
- 当前实现：回调里**忽略了** userId（只回调 roomName）。
- 这是有意的：业务上"别人加入"通常只需要 room 维度。
- 如果以后需要知道具体谁加入了，把 `onRemoteUserJoined(roomName, userId)` 改成两参数。

### 11.3 完整代码（增量）

```java
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
```

### 11.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `on("joined", ...)` | 监听"我加入成功" |
| `onUserJoined(room, userId)` | 回调上层 |
| `on("otherjoin", ...)` | 监听"别人加入" |
| `onRemoteUserJoined(room)` | 回调上层 |

### 11.5 阶段 11 后能干嘛
- 在 A、B 两个客户端上，A 进入房间后能收到 `onUserJoined`，B 进来时 A 收到 `onRemoteUserJoined`

---

## 12. 阶段 12 —— 加 "bye" / "leaved" / "full" 事件

### 12.1 本阶段目标
补全剩余房间事件：离开、别人离开、房间满。

### 12.2 遇到的新问题
**问题 1："leaved" 和 "bye" 的区别？**
- "leaved"：服务器通知**我自己**"你已经离开"（通常在我主动 leaveRoom 后收到）
- "bye"：服务器通知**我**"其他人已经离开"（被动接收）

**问题 2：full 事件的 userId 是谁的？**
- 当前约定：是我自己。因为是我尝试加入时发现满了。

### 12.3 完整代码（增量）

```java
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
```

### 12.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `on("bye", ...)` | 别人离开 |
| `on("leaved", ...)` | 自己离开确认 |
| `on("full", ...)` | 房间满 |

### 12.5 阶段 12 后能干嘛
- 房间生命周期事件全部覆盖
- UI 可以根据这些事件刷新"当前在线人数 / 参与者列表"

---

## 13. 阶段 13 —— 加 "message" 事件（SDP / candidate 通道）

### 13.1 本阶段目标
**关键里程碑**：让上层能收到对端发来的 SDP / candidate。

### 13.2 遇到的新问题
**问题 1：为什么这个事件最重要？**
- 答：这是 WebRTC 建链的"信道"。
- 没有它，SDP / candidate 传不到对端，P2P 永远建不起来。
- 前面所有事件都是辅助，这个事件才是核心。

**问题 2：args[1] 是 JSONObject，强转会不会崩？**
- 答：会。如果服务器发了非 JSON 数据，强转会抛 `ClassCastException`。
- 当前实现直接强转（信任服务器），生产环境建议加 instanceof 检查。

**问题 3：onMessage 接收的 JSONObject 内部字段谁解析？**
- 答：**上层**（通常 Activity）解析。
- SignalClient 只负责透传，不解析内容。
- 这是有意的：SignalClient 不耦合 WebRTC 业务逻辑。

### 13.3 完整代码（增量）

```java
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
```

### 13.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `on("message", ...)` | 监听 SDP / candidate 通道 |
| `(JSONObject) args[1]` | 拿到消息内容 |
| `onMessage(data)` | 透传给上层 |

### 13.5 阶段 13 后能干嘛
- **完整跑通 WebRTC 信令链路**：两端能交换 SDP 和 candidate
- 此时 PeerConnection.setRemoteDescription 能被正确调用

---

## 14. 阶段 14 —— URL 规范化（去末尾斜杠）

### 14.1 本阶段目标
把 URL 处理抽到方法开头，让 joinRoom 主体更清晰。

### 14.2 遇到的新问题
**问题 1：为什么必须现在做（而不是阶段 6）？**
- 答：阶段 6 已经做了 —— 这里只是"显式单独提一下，强调其重要性"。
- 实际上当前代码是阶段 6 就写好了，阶段 14 的代码已经在阶段 6 的代码块里。

**问题 2：为什么不直接 trim？**
- 答：`trim()` 会去掉首尾所有空白，可能误伤 URL 中合法的字符。
- 用 `endsWith("/") + substring` 更精确。

### 14.3 完整代码（已经在阶段 6 中）

```java
String wsUrl = url;
if (wsUrl.endsWith("/")) {
    wsUrl = wsUrl.substring(0, wsUrl.length() - 1);
}
```

### 14.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `endsWith("/")` 检查 | 防 Socket.IO 路径拼接错误 |
| `substring(0, length - 1)` | 去末尾斜杠 |

### 14.5 阶段 14 后能干嘛
- 输入 `https://server.com/` 和 `https://server.com` 行为一致

---

## 15. 阶段 15 —— OkHttpClient + 超时配置

### 15.1 本阶段目标
让 Socket.IO 底层用我们自己配置的 OkHttpClient（含超时）。

### 15.2 遇到的新问题
**问题 1：为什么不用 Socket.IO 默认的 OkHttpClient？**
- 答：默认 OkHttpClient 没有超时配置。服务器无响应时会一直 hang。
- 显式配置：连接 10s / 读 10s / 写 10s 都是兜底。

**问题 2：怎么让 Socket.IO 用我的 OkHttpClient？**
- 答：通过 `IO.Options`：
  - `options.callFactory = okHttpClient`（用于 polling fallback 时的 HTTP 请求）
  - `options.webSocketFactory = okHttpClient`（用于 WebSocket 握手）

### 15.3 完整代码（增量）

```java
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

// 在 joinRoom 中，URL 规范化之后加：

OkHttpClient.Builder okHttpBuilder = new OkHttpClient.Builder();
okHttpBuilder.connectTimeout(10, TimeUnit.SECONDS);
okHttpBuilder.readTimeout(10, TimeUnit.SECONDS);
okHttpBuilder.writeTimeout(10, TimeUnit.SECONDS);

OkHttpClient okHttpClient = okHttpBuilder.build();

// 配置到 IO.Options
IO.Options options = new IO.Options();
options.callFactory = okHttpClient;
options.webSocketFactory = okHttpClient;

mSocket = IO.socket(wsUrl, options);
```

### 15.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `OkHttpClient.Builder` | 自定义 HTTP 客户端 |
| 三个 10s 超时 | 防 hang |
| `options.callFactory = okHttpClient` | 注入 HTTP 工厂 |
| `options.webSocketFactory = okHttpClient` | 注入 WS 工厂 |

### 15.5 阶段 15 后能干嘛
- 服务器挂掉时 10 秒后报 CONNECT_ERROR，不再无限等待

---

## 16. 阶段 16 —— HTTPS / WSS 自签名证书支持

### 16.1 本阶段目标
**关键里程碑**：支持 HTTPS / WSS 自签名证书场景。

### 16.2 遇到的新问题
**问题 1：为什么要单独搞 SSL？**
- 答：开发环境的信令服务器经常用自签名证书。
- Android 默认不信任自签名证书，握手直接失败。
- 必须把自签名证书装进应用的 TrustManager 里。

**问题 2：证书放哪儿？**
- 答：`res/raw/certificate.crt`（PEM 格式）。
- 代码通过 `mContext.getResources().openRawResource(R.raw.certificate)` 加载。

**问题 3：为什么 hostnameVerifier 信任所有？**
- 答：自签名证书通常 hostname 也对不上。
- 生产环境应该按 hostname 严格校验，这里是开发妥协。

**问题 4：失败时为什么不抛出？**
- 答：让 SSL 失败"软着陆"。如果证书加载失败，OkHttpClient 还是用默认 TrustManager 走。
- 但实际上 WSS 还是连不上。所以这个 catch 是"log 后继续"，不是"恢复"。

### 16.3 完整代码（增量）

```java
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

// 在 OkHttpClient.Builder 之后、build() 之前插入：

if (url.startsWith("https") && mContext != null) {
    try {
        // 1. 加载证书
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        InputStream certInput = mContext.getResources().openRawResource(R.raw.certificate);
        Certificate ca = cf.generateCertificate(certInput);
        certInput.close();

        // 2. 装入 KeyStore
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca", ca);

        // 3. 创建 TrustManager
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        X509TrustManager trustManager = (X509TrustManager) tmf.getTrustManagers()[0];

        // 4. 创建 SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), null);

        // 5. 配置到 OkHttpClient
        okHttpBuilder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
        okHttpBuilder.hostnameVerifier((hostname, session) -> true);

        Log.i(TAG, "SSL Certificate loaded successfully");
    } catch (Exception e) {
        Log.e(TAG, "Failed to load SSL certificate: " + e.getMessage());
    }
}
```

### 16.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `url.startsWith("https")` 检查 | 只在 HTTPS 时启用自定义 SSL |
| `CertificateFactory.getInstance("X.509")` | 解析 PEM 证书 |
| `openRawResource(R.raw.certificate)` | 从 res/raw 加载证书 |
| `KeyStore.setCertificateEntry("ca", ca)` | 把证书装入信任库 |
| `SSLContext.init(...)` | 创建 SSL 上下文 |
| `sslSocketFactory(...)` | 配置 OkHttp 用自定义 SSL |
| `hostnameVerifier(... -> true)` | 信任所有 hostname |
| try/catch 包整体 | 失败时 log 不抛 |

### 16.5 阶段 16 后能干嘛
- 能连自签名 HTTPS 服务器
- 这是开发环境的标配

### 16.6 关键安全警告
**`hostnameVerifier(... -> true)` 在生产环境绝对禁用**。
当前是开发环境妥协，正式上线时必须改成按 hostname 严格校验。

---

## 17. 阶段 17 —— 配置 IO.Options（transport / reconnection / factory）

### 17.1 本阶段目标
把所有 IO.Options 字段配齐。

### 17.2 遇到的新问题
**问题 1：transports 为什么要只设 websocket？**
- 答：默认 Socket.IO 会先 polling 再 upgrade 到 websocket。
- Polling 是 HTTP 长轮询，效率差。我们已经有 OkHttpClient 支持 WS，直接走 WS 跳过 polling。

**问题 2：reconnection 为什么要 true？**
- 答：网络抖动是常态。开启自动重连，应用从后台切回来不用手动重连。

### 17.3 完整代码（增量）

```java
IO.Options options = new IO.Options();
options.transports = new String[]{"websocket"};
options.reconnection = true;
options.callFactory = okHttpClient;
options.webSocketFactory = okHttpClient;
```

### 17.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `transports = ["websocket"]` | 跳过 polling，直接 WS |
| `reconnection = true` | 自动重连 |

### 17.5 阶段 17 后能干嘛
- **与当前文件结构完全一致**
- 所有 IO.Options 字段都配齐

---

## 18. 完整最终代码对照

到阶段 17 为止，代码已经和文件 `app/src/main/java/com/example/chapter07/SignalClient.java` 完全一致。下面是按行号 + 阶段映射的关系：

| 阶段 | 文件中对应的内容 | 行号（参考） |
|------|----------------|------------|
| 1 | `OnSignalEventListener` 接口（9 个回调） | 30-44 |
| 2 | 单例 + 字段 + 私有构造 + getInstance | 26-28, 46-54 |
| 3 | init / setSignalEventListener | 64-72 |
| 6 | joinRoom 主体 + URL 规范化 + IO.socket | 74-127, 209-216 |
| 7 | EVENT_CONNECT + onConnected + emit join | 156-167 |
| 8 | EVENT_DISCONNECT + EVENT_CONNECT_ERROR | 169-186 |
| 9 | sendMessage | 224-232 |
| 10 | leaveRoom | 240-245 |
| 11 | joined + otherjoin | 188-200 |
| 12 | bye + leaved + full | 200-205 |
| 13 | message 事件（SDP / candidate 通道） | 205-209 |
| 14 | URL 规范化 | 78-82 |
| 15 | OkHttpClient.Builder + 三个超时 | 87-91 |
| 16 | HTTPS 自签名证书支持 | 92-127 |
| 17 | IO.Options（transports / reconnection） | 130-135 |

最终代码全文已在前面各阶段给出，结构和当前文件完全一致。

---

## 19. 演进回顾表

| 阶段 | 加了什么 | 行数变化 | 解决的痛点 | 阶段末能干嘛 |
|------|---------|---------|-----------|------------|
| 1 | OnSignalEventListener 接口 | ~30 | 上层能 mock | 编译通过 |
| 2 | 单例 + 字段 | ~50 | 全局唯一 | getInstance 能跑 |
| 3 | init / setListener | ~70 | 注入依赖 | 准备连接 |
| 6 | joinRoom 主体 + IO.socket | ~110 | 连接到服务器 | 物理连通 |
| 7 | EVENT_CONNECT + 自动 join | ~125 | 自动加入房间 | 服务器知道"我来了" |
| 8 | EVENT_DISCONNECT / ERROR | ~140 | 状态可见 | UI 能响应 |
| 9 | sendMessage | ~150 | 发 SDP / candidate | 信令上行 |
| 10 | leaveRoom | ~160 | 优雅退出 | 完整会话周期 |
| 11 | joined / otherjoin | ~180 | 房间事件 | 知道房间里谁 |
| 12 | bye / leaved / full | ~200 | 完整房间事件 | 全生命周期 |
| 13 | **message 事件** | ~215 | **信令通道** | **能建 P2P** |
| 14 | URL 规范化 | ~215 | 路径一致 | 已经在阶段 6 |
| 15 | OkHttpClient + 超时 | ~225 | 防 hang | 网络差也能超时 |
| 16 | **SSL 自签名证书** | ~265 | HTTPS 开发 | 自签名服务器能连 |
| 17 | IO.Options 完整配置 | ~268 | 完整 | 与当前文件一致 |

**关键里程碑**：
- 阶段 13 = 能跑通 WebRTC 信令的核心链路
- 阶段 16 = 在自签名 HTTPS 服务器环境下能连
- 阶段 17 = 与当前文件结构完全一致

---

## 20. 常见报错对照表

写代码时遇到下面任何一种错误，对照表看哪里出问题：

| 错误 | 阶段 | 原因 + 修复 |
|------|------|------------|
| `URISyntaxException` | 6 | URL 格式非法（缺端口、含中文等） |
| `RuntimeException: xhr poll error` | 6/15 | 服务器地址不通或 HTTPS 没配 SSL |
| `Socket.IO disconnected immediately` | 8 | 服务器拒绝连接（鉴权失败、CORS 等） |
| `SSL handshake failed` | 16 | 自签名证书加载失败。检查 R.raw.certificate 是否存在 |
| `Trust anchor for certification path not found` | 16 | 证书没装进 TrustManager。检查 `tmf.init(keyStore)` |
| `Hostname X not verified` | 16 | hostnameVerifier 太严。当前是 true，应该跳过 |
| `Cannot emit: socket not connected` | 9 | joinRoom 还没成功发 join 就 sendMessage 了 |
| `ClassCastException: cannot cast to JSONObject` | 13 | 服务器发的 message 不是 JSON |
| 别人加入房间但我收不到 `otherjoin` | 11 | 服务器没广播，或者房间命名不一致 |
| 应用切后台回来收不到消息 | 17 | reconnection=true 但服务器没保留房间状态 |
| 加入房间一直收 `full` | 12 | 服务器认为房间人数已满（业务逻辑问题） |

---

## 附录 A：线程安全的单例写法

```java
// 当前实现（非线程安全，但 Android 上 UI 线程访问够用）
public static SignalClient getInstance() {
    if (mInstance == null) {
        mInstance = new SignalClient();
    }
    return mInstance;
}

// 严格线程安全版本（双重检查锁）
private static volatile SignalClient mInstance;

public static SignalClient getInstance() {
    if (mInstance == null) {
        synchronized (SignalClient.class) {
            if (mInstance == null) {
                mInstance = new SignalClient();
            }
        }
    }
    return mInstance;
}
```

---

## 附录 B：相关文件

| 文件 | 作用 |
|------|------|
| `app/src/main/java/com/example/chapter07/SignalClient.java` | 本指南所构建的类，268 行最终形态 |
| `app/src/main/java/com/example/chapter07/peer.java` | 在 Builder / 按钮回调处实例化 SignalClient 的入口 |
| `app/src/main/res/raw/certificate.crt` | HTTPS 自签名证书（阶段 16 用） |
| `readme/record_audio/README.md` | 录制相关的总览 |
