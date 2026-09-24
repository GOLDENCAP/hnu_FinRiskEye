package com.example.chapter07;  // 声明类所在的包名

// ========== Android 框架相关 ==========
import android.content.Context;  // Android 上下文，用于访问 raw 资源下的证书文件

// ========== Java 标准库：IO / 安全 / SSL ==========
import java.io.InputStream;                       // 输入流，用于读取证书文件
import java.security.KeyStore;                    // 密钥库，存放受信任的 CA 证书
import java.security.cert.Certificate;            // 证书对象
import java.security.cert.CertificateFactory;     // 证书工厂，用于解析 X.509 证书

import javax.net.ssl.SSLContext;                  // SSL/TLS 上下文，提供 SocketFactory
import javax.net.ssl.TrustManagerFactory;        // 信任管理器工厂，用于创建 TrustManager

/**
 * SSLSocketUtils：SSL/TLS 工具类，加载项目自带的自签名 CA 证书并构建 SSLContext。
 *
 * 用途：在使用 HTTPS / WSS 自签名证书的信令服务器时，让 OkHttp / Socket.IO 信任该证书。
 *
 * 注意：本类与 SSLUtils 功能相似，但仅返回 SSLContext，不暴露 TrustManager。
 */
public class SSLSocketUtils {  // SSL 工具类

    /**
     * 构造一个信任项目自带证书的 SSLContext。
     *
     * 实现步骤：
     *   1. 从 res/raw/certificate 读取 X.509 证书
     *   2. 用 KeyStore 装载证书
     *   3. 通过 TrustManagerFactory 创建 TrustManager
     *   4. 用 TrustManager 初始化 SSLContext
     *
     * @param context Android 上下文，用于访问 R.raw.certificate
     * @return 构造好的 SSLContext；发生异常时返回 null
     */
    public static SSLContext createSSLContext(Context context) {
        try {
            // ===== 1. 加载 X.509 证书 =====
            CertificateFactory cf = CertificateFactory.getInstance("X.509");                 // X.509 证书工厂
            InputStream certInput = context.getResources().openRawResource(R.raw.certificate); // 打开 raw 资源下的证书文件
            Certificate ca = cf.generateCertificate(certInput);                              // 解析为 Certificate 对象
            certInput.close();                                                               // 关闭输入流

            // ===== 2. 创建 KeyStore 并加入证书 =====
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());             // 默认类型（Android 通常是 BKS）
            keyStore.load(null, null);                                                       // 初始化为空 keystore
            keyStore.setCertificateEntry("ca", ca);                                          // 以别名 "ca" 加入证书

            // ===== 3. 创建 TrustManagerFactory =====
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());                              // 默认算法（PKIX）
            tmf.init(keyStore);                                                              // 用 keystore 初始化

            // ===== 4. 创建 SSLContext =====
            SSLContext sslContext = SSLContext.getInstance("TLS");                            // 使用 TLS 协议
            sslContext.init(null, tmf.getTrustManagers(), null);                              // 初始化（只设置信任管理器）
            return sslContext;                                                               // 返回
        } catch (Exception e) {                                                              // 任何异常
            e.printStackTrace();                                                              // 打印堆栈
            return null;                                                                      // 返回 null
        }
    }
}