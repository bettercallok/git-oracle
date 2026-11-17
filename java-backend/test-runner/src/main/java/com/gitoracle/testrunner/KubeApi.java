package com.gitoracle.testrunner;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * The few Kubernetes API calls the test sandbox needs, made with the pod's own
 * service account. A full client library would add tens of megabytes to a JVM
 * that runs with a 320MB heap, for five REST calls.
 *
 * <p>The token is re-read on every request: projected service-account tokens
 * rotate, and a token read once at startup stops working after an hour.
 */
class KubeApi {

    static final Path SA_DIR = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");

    record Response(int status, String body) {
        boolean ok() { return status >= 200 && status < 300; }
    }

    private final String baseUrl;
    private final Path tokenFile;
    private final HttpClient http;

    KubeApi(String baseUrl, Path tokenFile, HttpClient http) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.tokenFile = tokenFile;
        this.http = http;
    }

    /** The API server as seen from inside a pod, trusting only the cluster's CA. */
    static KubeApi inCluster() throws Exception {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("KUBERNETES_SERVICE_HOST is not set: not running in a cluster");
        }
        String hostPart = host.contains(":") ? "[" + host + "]" : host;
        return of("https://" + hostPart + ":" + (port == null || port.isBlank() ? "443" : port),
            SA_DIR.resolve("token"), SA_DIR.resolve("ca.crt"));
    }

    /** An API server at {@code baseUrl}, trusting only the CA in {@code caFile}. */
    static KubeApi of(String baseUrl, Path tokenFile, Path caFile) throws Exception {
        return new KubeApi(baseUrl, tokenFile, clientTrusting(caFile));
    }

    /** The namespace this pod runs in. */
    static String ownNamespace() throws IOException {
        return Files.readString(SA_DIR.resolve("namespace")).trim();
    }

    private static HttpClient clientTrusting(Path caFile) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(caFile)) {
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trust.setCertificateEntry("ca-" + i++, (X509Certificate) cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(10)).build();
    }

    Response get(String path) throws IOException, InterruptedException {
        return send("GET", path, null);
    }

    Response post(String path, String json) throws IOException, InterruptedException {
        return send("POST", path, json);
    }

    Response delete(String path) throws IOException, InterruptedException {
        return send("DELETE", path, null);
    }

    private Response send(String method, String path, String json) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + Files.readString(tokenFile).trim());
        if (json != null) {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
