package dev.airtv.airdrop;

import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

/** Loopback-only fixture. It accepts ONLY synthetic integration-test offers. */
public final class LabMain {
    public static void main(String[] args) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        char[] password = "lab-only".toCharArray();
        try (InputStream input = new FileInputStream(args[0])) { keys.load(input, password); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password);
        X509TrustManager peerCertificates = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String kind) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("AirDrop client certificate required");
                chain[0].checkValidity();
            }
            public void checkServerTrusted(X509Certificate[] chain, String kind) throws CertificateException { throw new CertificateException(); }
        };
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), new TrustManager[]{peerCertificates}, new SecureRandom());
        File files = new File(args[1]);
        try (AirDropReceiver receiver = new AirDropReceiver(tls, InetAddress.getByName("127.0.0.1"), 0,
                "Air TV Lab", files, (sender, offered) -> sender.equals("AirTV synthetic test"),
                directory -> System.out.println("COMMITTED " + directory.getName()))) {
            receiver.start(); System.out.println("PORT " + receiver.port()); System.out.flush();
            System.in.read();
        }
    }
}
