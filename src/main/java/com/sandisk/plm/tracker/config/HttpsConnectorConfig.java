package com.sandisk.plm.tracker.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.util.logging.Logger;

/**
 * Adds an HTTPS connector to the embedded Tomcat alongside the existing HTTP
 * connector on {@code server.port}. Off by default — set {@code app.https.enabled=true}
 * in {@code config/application.properties} to turn it on. Both ports stay live, so
 * the existing {@code http://host:8090/} URL keeps working AND {@code https://host:8443/}
 * starts serving the same content (required for browser-native features like the
 * mic / Web Speech API which only run in secure contexts).
 *
 * <p>One-time keystore prep on the host (from a shell with Java's {@code keytool} on PATH):
 * <pre>
 *   keytool -genkeypair -alias toolkit -keyalg RSA -keysize 2048 \
 *           -storetype PKCS12 -keystore toolkit.p12 \
 *           -validity 3650 -storepass toolkit-tls \
 *           -dname "CN=uls-ep-aglipccb.sandisk.com,O=SanDisk,C=US" \
 *           -ext "SAN=DNS:uls-ep-aglipccb.sandisk.com,DNS:uls-ep-aglipccb"
 * </pre>
 * Drop the resulting {@code toolkit.p12} next to the JAR (or wherever
 * {@code app.https.keystore} points) and bounce.
 */
@Configuration
@ConditionalOnProperty(name = "app.https.enabled", havingValue = "true")
public class HttpsConnectorConfig {

    private static final Logger logger = Logger.getLogger(HttpsConnectorConfig.class.getName());

    @Value("${app.https.port:8443}")
    private int httpsPort;

    @Value("${app.https.keystore}")
    private String keystorePath;

    @Value("${app.https.keystore-password}")
    private String keystorePassword;

    @Value("${app.https.keystore-type:PKCS12}")
    private String keystoreType;

    @Value("${app.https.key-alias:toolkit}")
    private String keyAlias;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpsConnectorCustomizer() {
        return factory -> {
            // Resolve the keystore path against the JVM working directory if it's relative.
            // Without this Tomcat resolves it against catalina.base (a temp work dir like
            // C:\Users\<user>\AppData\Local\Temp\3\tomcat.8090.NNN\), which never contains
            // the keystore — boot fails with FileNotFoundException for "./toolkit.p12".
            File ksFile = new File(keystorePath);
            String resolvedKeystore = ksFile.isAbsolute() ? keystorePath : ksFile.getAbsolutePath();

            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setScheme("https");
            connector.setSecure(true);
            connector.setPort(httpsPort);

            Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
            protocol.setSSLEnabled(true);
            protocol.setKeystoreFile(resolvedKeystore);
            protocol.setKeystorePass(keystorePassword);
            protocol.setKeystoreType(keystoreType);
            protocol.setKeyAlias(keyAlias);

            factory.addAdditionalTomcatConnectors(connector);
            logger.info("[HTTPS] enabled on port " + httpsPort + " (keystore=" + resolvedKeystore +
                        ", alias=" + keyAlias + "). HTTP connector on " +
                        "${server.port} continues to serve as before.");
        };
    }
}
