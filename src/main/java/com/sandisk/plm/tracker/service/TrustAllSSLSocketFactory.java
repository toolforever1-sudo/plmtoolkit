package com.sandisk.plm.tracker.service;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;

public class TrustAllSSLSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TrustAllSSLSocketFactory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }}, new java.security.SecureRandom());
            delegate = ctx.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create TrustAll SSL factory", e);
        }
    }

    // JNDI requires this static method
    public static SocketFactory getDefault() {
        return new TrustAllSSLSocketFactory();
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return delegate.createSocket(s, host, port, autoClose); }
    @Override public Socket createSocket(String host, int port) throws IOException { return delegate.createSocket(host, port); }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException { return delegate.createSocket(host, port, localHost, localPort); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return delegate.createSocket(host, port); }
    @Override public Socket createSocket(InetAddress addr, int port, InetAddress localAddr, int localPort) throws IOException { return delegate.createSocket(addr, port, localAddr, localPort); }
}
