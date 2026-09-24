package com.example.chapter07;  // 声明类所在的包名

// ========== Android 框架相关 ==========
import android.content.Context;  // Android 上下文，用于访问 raw 资源下的证书文件

// ========== Java 标准库：IO / 安全 / SSL ==========
import java.io.InputStream;                       // 输入流，用于读取证书文件
import java.security.KeyStore;                    // 密钥库，存放受信任的 CA 证书
import java.security.cert.Certificate;            // 证书对象
import java.security.cert.CertificateFactory;     // 证书工厂，用于解析 X.509 证书

import javax.net.ssl.SSLContext;                  // SSL/TLS 上下文
import javax.net.ssl.TrustManagerFactory;        // 信任管理器工厂
import javax.net.ssl.X509TrustManager;            // X.509 信任管理器（OkHttp 需要）

/**
 * SSLUtils：SSL/TLS 工具类（增强版）。
 *
 * 提供两个方法：
 *   1. createSSLContextWithCertificate()：返回带 TrustManager 的 SSLContext
 *   2. getTrustManager()：单独返回 X509TrustManager（OkHttp 的 sslSocketFactory 需要 X509TrustManager）
 *
 * 与 SSLSocketUtils 区别：本类额外暴露了 getTrustManager()，方便 OkHttp 直接使用。
 */
public class SSLUtils {  // SSL 工具类

    /**
     * 构造一个信任项目自带证书的 SSLContext。
     *
     * @param context Android 上下文
     * @return SSLContext；异常时返回 null
     */
    public static SSLContext createSSLContextWithCertificate(Context context) {
        try {
            // ===== 1. 加载 X.509 证书 =====
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            InputStream certInput = context.getResources().openRawResource(R.raw.certificate); // 读取 R.raw.certificate
            Certificate ca = cf.generateCertificate(certInput);                              // 解析证书
            certInput.close();

            // ===== 2. 创建 KeyStore 并加入证书 =====
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);                                                       // 初始化为空
            keyStore.setCertificateEntry("ca", ca);                                          // 添加证书

            // ===== 3. 创建 TrustManagerFactory =====
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            // ===== 4. 创建 SSLContext =====
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 获取一个 X509TrustManager（专门给 OkHttp.sslSocketFactory 使用）。
     *
     * @param context Android 上下文
     * @return X509TrustManager；异常时返回 null
     */
    public static X509TrustManager getTrustManager(Context context) {
        try {
            // 与上面方法相同的加载流程
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

            // 直接取第一个 TrustManager 并转型（默认就是 X509TrustManager）
            return (X509TrustManager) tmf.getTrustManagers()[0];
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}