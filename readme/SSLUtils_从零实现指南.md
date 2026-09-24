# 从零实现 SSLUtils —— 渐进式构建指南

> **目标读者**：已经读过 SSLSocketUtils 的实现、对 Java SSL 基础有概念的人。
>
> **本文目标**：把当前 `app/src/main/java/com/example/chapter07/SSLUtils.java`（85 行）从"空类"演进到当前规模。它是 `SSLSocketUtils` 的"增强版"，多暴露了一个 `getTrustManager()` 方法。
>
> **与 SSLSocketUtils 的关系**：本章不重复讲解基础流程，专注讲"为什么要多一个 `getTrustManager()`"。

---

## 目录

1. [这个类到底要做什么](#1-这个类到底要做什么)
2. [与 SSLSocketUtils 的关键区别](#2-与-sslsocketutils-的关键区别)
3. [前置知识速查](#3-前置知识速查)
4. [阶段 1 —— 空类](#4-阶段-1--空类)
5. [阶段 2 —— 复制 SSLSocketUtils 的逻辑（createSSLContextWithCertificate）](#5-阶段-2--复制-sslsocketutils-的逻辑createsslcontextwithcertificate)
6. [阶段 3 —— 提取通用方法：loadCertificate](#6-阶段-3--提取通用方法loadcertificate)
7. [阶段 4 —— 提取通用方法：buildTrustManager](#7-阶段-4--提取通用方法buildtrustmanager)
8. [阶段 5 —— 加 getTrustManager 方法](#8-阶段-5--加-gettrustmanager-方法)
9. [完整最终代码对照](#9-完整最终代码对照)
10. [演进回顾表](#10-演进回顾表)
11. [常见问答](#11-常见问答)

---

## 1. 这个类到底要做什么

一句话：**同 SSLSocketUtils 一样加载自签名证书，但额外暴露一个 `getTrustManager()` 方法，专门给 OkHttp 用。**

为什么需要 OkHttp 专用？因为 OkHttp 的 `sslSocketFactory(...)` 方法签名是：
```java
sslSocketFactory(SSLSocketFactory sslSocketFactory, X509TrustManager trustManager)
```

它需要 **`X509TrustManager`**（具体类型），而 SSLSocketUtils 只给你 `SSLContext`，调用方还得自己从 `SSLContext` 里把 TrustManager 取出来。

这个类的价值就是把"取 TrustManager"这一步也封装好。

---

## 2. 与 SSLSocketUtils 的关键区别

| 对比维度 | SSLSocketUtils | SSLUtils |
|---------|---------------|----------|
| 公开方法 | `createSSLContext(Context)` | `createSSLContextWithCertificate(Context)` + `getTrustManager(Context)` |
| 暴露 TrustManager？ | ❌ 调用方要自己从 SSLContext 拿 | ✅ 直接给 X509TrustManager |
| 适合谁用 | Socket.IO、自定义 SSL | **OkHttp** |
| 是否重复加载证书？ | ❌ 一次 | ❌ 一次（每次调用都重新加载，但逻辑简单） |

**关键结论**：如果用 OkHttp，**优先用 SSLUtils**，因为它省了你转型 `TrustManager` 的麻烦。

---

## 3. 前置知识速查

### 3.1 OkHttp 需要什么

OkHttp 配置自定义 SSL 的两步：
```java
SSLContext sslContext = SSLUtils.createSSLContextWithCertificate(ctx);
X509TrustManager tm = SSLUtils.getTrustManager(ctx);  // ← 这就是 SSLUtils 多出来的那步

okHttpBuilder.sslSocketFactory(sslContext.getSocketFactory(), tm);
okHttpBuilder.hostnameVerifier((h, s) -> true);
```

OkHttp 之所以要 `X509TrustManager` 而不是 `TrustManager`，是因为它在内部会调用 `tm.checkServerTrusted(...)` 做 hostname 验证（虽然我们这里绕过了 hostname 验证）。

### 3.2 TrustManager vs X509TrustManager

```java
public interface TrustManager { }                  // 父接口（空）

public interface X509TrustManager extends TrustManager {  // 子接口（具体）
    void checkClientTrusted(X509Certificate[] chain, String authType);
    void checkServerTrusted(X509Certificate[] chain, String authType);
    X509Certificate[] getAcceptedIssuers();
}
```

`TrustManagerFactory.getTrustManagers()` 返回 `TrustManager[]`，第一个元素就是 `X509TrustManager`（在 PKIX 算法下）。

---

## 4. 阶段 1 —— 空类

### 4.1 本阶段目标
占位，调用方能 import。

### 4.2 完整代码

```java
package com.example.chapter07;

public class SSLUtils {
}
```

### 4.3 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `public class SSLUtils` | 占位 |

---

## 5. 阶段 2 —— 复制 SSLSocketUtils 的逻辑（createSSLContextWithCertificate）

### 5.1 本阶段目标
先把 SSLContext 创建出来，方法和 SSLSocketUtils 几乎一样。

### 5.2 遇到的新问题
**问题 1：方法名为啥不一样？**
- SSLSocketUtils 用 `createSSLContext`
- SSLUtils 用 `createSSLContextWithCertificate`
- 差别在于 SSLUtils 还有 `getTrustManager` 方法，所以重命名以示区分

**问题 2：为什么不直接继承 SSLSocketUtils？**
- 答：因为两个类的公开 API 不完全一样。
- 继承会让方法名错乱（父类 `createSSLContext`，子类想叫 `createSSLContextWithCertificate`）。
- 工具类之间**用组合/复制**比继承清晰。

### 5.3 完整代码（增量）

```java
import android.content.Context;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class SSLUtils {
    public static SSLContext createSSLContextWithCertificate(Context context) {
        try {
            // 步骤 1：加载 X.509 证书
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream certInput = context.getResources().openRawResource(R.raw.certificate);
            Certificate ca = cf.generateCertificate(certInput);
            certInput.close();

            // 步骤 2：KeyStore
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", ca);

            // 步骤 3：TrustManagerFactory
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            // 步骤 4：SSLContext
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
```

### 5.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `createSSLContextWithCertificate` | 创建 SSL 上下文（与 SSLSocketUtils 等价） |

### 5.5 阶段 2 后能干嘛
- 调用方能拿到 SSLContext
- 但拿到 TrustManager 还得转型，下一步解决

---

## 6. 阶段 3 —— 提取通用方法：loadCertificate

### 6.1 本阶段目标
把"加载证书 + 装入 KeyStore"这段逻辑抽出来，让两个方法复用。

### 6.2 遇到的新问题
**问题 1：要不要重构？**
- 答：要。
- 因为接下来 `getTrustManager` 也要"加载证书 + 装 KeyStore"，代码重复会很难维护。
- 提前抽 `loadCertificate`，让两个方法都调它。

**问题 2：抽到什么粒度？**
- 抽到"返回 KeyStore"即可，不要抽到"返回 TrustManager"
- 因为下一步还要用 KeyStore 创 TrustManager，不能越过这步

### 6.3 完整代码（增量）

```java
/**
 * 加载证书到 KeyStore。
 */
private static KeyStore loadCertificate(Context context) throws Exception {
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    InputStream certInput = context.getResources().openRawResource(R.raw.certificate);
    Certificate ca = cf.generateCertificate(certInput);
    certInput.close();

    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    keyStore.load(null, null);
    keyStore.setCertificateEntry("ca", ca);
    return keyStore;
}
```

### 6.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `loadCertificate` 私有方法 | 提取公共逻辑 |
| `throws Exception` | 让调用方决定如何处理异常 |

### 6.5 阶段 3 后能干嘛
- `createSSLContextWithCertificate` 和将来的 `getTrustManager` 都可以直接调 `loadCertificate`
- 不再重复写证书加载代码

---

## 7. 阶段 4 —— 提取通用方法：buildTrustManager

### 7.1 本阶段目标
把"从 KeyStore 创 TrustManager"也抽出来。

### 7.2 遇到的新问题
**问题 1：要不要再抽一层？**
- 答：要。
- `createSSLContextWithCertificate` 和 `getTrustManager` 都用到 TrustManagerFactory → TrustManager 这步。

**问题 2：返回 TrustManager[] 还是 X509TrustManager？**
- 当前阶段返回 `TrustManager[]`（保留通用性）
- 等 `getTrustManager` 时再转 `X509TrustManager`

### 7.3 完整代码（增量）

```java
import javax.net.ssl.TrustManager;

private static TrustManager[] buildTrustManager(KeyStore keyStore) throws Exception {
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(keyStore);
    return tmf.getTrustManagers();
}
```

### 7.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `buildTrustManager` | 复用 TrustManagerFactory 逻辑 |

### 7.5 阶段 4 后能干嘛
- 接下来 `getTrustManager` 可以一行代码搞定

---

## 8. 阶段 5 —— 加 getTrustManager 方法

### 8.1 本阶段目标
补上 `getTrustManager` 方法，直接返回 `X509TrustManager`。

### 8.2 遇到的新问题
**问题 1：转型会不会失败？**
- 在 PKIX 算法下，`tmf.getTrustManagers()[0]` 一定是 `X509TrustManager`
- 但编译器不知道，需要强转
- 万一 Android 改了默认算法，可能会抛 `ClassCastException`
- 当前实现直接转（信任现状）

**问题 2：getTrustManagers()[0] 还是 getTrustManagers() 整个返回？**
- OkHttp 的 `sslSocketFactory` 只接受一个 `X509TrustManager`
- 所以返回 `[0]` 即可

### 8.3 完整代码（最终）

```java
import javax.net.ssl.X509TrustManager;

public static X509TrustManager getTrustManager(Context context) {
    try {
        KeyStore keyStore = loadCertificate(context);
        TrustManager[] trustManagers = buildTrustManager(keyStore);
        return (X509TrustManager) trustManagers[0];
    } catch (Exception e) {
        e.printStackTrace();
        return null;
    }
}
```

### 8.4 加了什么 + 为什么

| 新增内容 | 解决的问题 |
|---------|-----------|
| `getTrustManager` 公开方法 | OkHttp 专用 |
| `(X509TrustManager) trustManagers[0]` | 把通用 TrustManager 转成具体 X509TrustManager |

### 8.5 阶段 5 后能干嘛
- OkHttp 配置 SSL 时一行搞定：
  ```java
  okHttpBuilder.sslSocketFactory(
      SSLUtils.createSSLContextWithCertificate(ctx).getSocketFactory(),
      SSLUtils.getTrustManager(ctx)
  );
  ```

---

## 9. 完整最终代码对照

到阶段 5 为止，与 `app/src/main/java/com/example/chapter07/SSLUtils.java` 完全一致：

```java
package com.example.chapter07;

import android.content.Context;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class SSLUtils {

    public static SSLContext createSSLContextWithCertificate(Context context) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream certInput = context.getResources().openRawResource(R.raw.certificate);
            Certificate ca = cf.generateCertificate(certInput);
            certInput.close();

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", ca);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static X509TrustManager getTrustManager(Context context) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream certInput = context.getResources().openRawResource(R.raw.certificate);
            Certificate ca = cf.generateCertificate(certInput);
            certInput.close();

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", ca);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            return (X509TrustManager) tmf.getTrustManagers()[0];
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
```

（注：当前实现**没有真正抽公共方法**，而是两个方法各自完整复制了一遍证书加载代码。这是为了清晰可读，重构空间留给读者。）

---

## 10. 演进回顾表

| 阶段 | 加了什么 | 行数 | 解决了什么 |
|------|---------|------|-----------|
| 1 | 空类 | 3 | 占位 |
| 2 | `createSSLContextWithCertificate` | 38 | SSLContext 给谁用都行 |
| 3 | `loadCertificate`（私有，重构建议） | 12 | 复用加载逻辑 |
| 4 | `buildTrustManager`（私有，重构建议） | 8 | 复用 TrustManager 构建 |
| 5 | `getTrustManager` | 22 | OkHttp 专用 |

**关键里程碑**：
- 阶段 2 = 与 SSLSocketUtils 等价
- 阶段 5 = OkHttp 一行可用

---

## 11. 常见问答

### Q1：SSLUtils 和 SSLSocketUtils 能不能合并成一个？
A：技术上能。但当前项目保留两个，是为了：
- SSLSocketUtils 给"我只要 SSLContext"的调用方
- SSLUtils 给"我用 OkHttp，要 TrustManager"的调用方

合并后调用方代码会更长（拿到 SSLContext 再自己取 TrustManager）。

### Q2：每次调用都重新加载证书，是不是很慢？
A：是的，证书加载涉及 IO + 解析。但只在建立 SSL 连接时调用一次（连接建立后就缓存了），不会成为热点。

如果想优化，可以改成"单例 + 缓存"：
```java
private static SSLContext sCachedContext;

public static synchronized SSLContext createSSLContextWithCertificate(Context context) {
    if (sCachedContext == null) {
        sCachedContext = createSSLContextWithCertificateInternal(context);
    }
    return sCachedContext;
}
```

### Q3：为什么返回 null 而不是抛异常？
A：调用方代码可以写得简洁：
```java
SSLContext ctx = SSLUtils.createSSLContextWithCertificate(this);
if (ctx != null) {
    okHttpBuilder.sslSocketFactory(ctx.getSocketFactory(), SSLUtils.getTrustManager(this));
}
```

### Q4：要不要把 hostname 校验也封装进来？
A：当前不封装。`hostnameVerifier((h, s) -> true)` 是开发妥协，生产环境应该按 hostname 严格校验。封装进 SSLUtils 会让"开关"语义模糊。
