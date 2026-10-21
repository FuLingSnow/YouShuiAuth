package net.rafalohaki.veloauth.qq;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QqBridgeServerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String GROUP = "123456";
    private static final String QQ_ID = "10001";

    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger nonceCounter = new AtomicInteger();
    private QqBridgeServer server;
    private FakeBridge bridge;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        port = freePort();
        QqSettings settings = new QqSettings(true, "127.0.0.1", port, SECRET,
                Set.of(GROUP), 300, 120, 5, "", true, 120);
        bridge = new FakeBridge();
        server = new QqBridgeServer(settings, bridge);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void deviceApprovalAcceptsSignedDecision() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("platform", "qq");
        body.addProperty("groupId", GROUP);
        body.addProperty("qqId", QQ_ID);
        body.addProperty("decision", "Y");
        body.addProperty("approvalId", "approval-token-1");

        HttpResponse<String> response = post("/internal/qq/device-approval",
                body.toString(), true);

        assertEquals(200, response.statusCode());
        assertEquals("ok", JsonParser.parseString(response.body())
                .getAsJsonObject().get("code").getAsString());
        assertEquals("Y", bridge.lastDecision);
        assertEquals(QQ_ID, bridge.lastQqId);
        assertEquals("approval-token-1", bridge.lastApprovalId);
    }

    @Test
    void deviceApprovalRejectsBadSignature() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("groupId", GROUP);
        body.addProperty("qqId", QQ_ID);
        body.addProperty("decision", "Y");
        body.addProperty("approvalId", "approval-token-1");

        HttpResponse<String> response = post("/internal/qq/device-approval",
                body.toString(), false);

        assertEquals(401, response.statusCode());
        assertNull(bridge.lastDecision);
    }

    @Test
    void deviceApprovalRejectsForeignGroup() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("groupId", "999999");
        body.addProperty("qqId", QQ_ID);
        body.addProperty("decision", "Y");
        body.addProperty("approvalId", "approval-token-1");

        HttpResponse<String> response = post("/internal/qq/device-approval",
                body.toString(), true);

        assertEquals(200, response.statusCode());
        assertEquals("group_not_allowed", JsonParser.parseString(response.body())
                .getAsJsonObject().get("code").getAsString());
        assertNull(bridge.lastDecision);
    }

    @Test
    void stopAutoLoginAcceptsSignedRequest() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("platform", "qq");
        body.addProperty("groupId", GROUP);
        body.addProperty("qqId", QQ_ID);
        body.addProperty("stopId", "stop-token-1");

        HttpResponse<String> response = post("/internal/qq/stop-auto-login",
                body.toString(), true);

        assertEquals(200, response.statusCode());
        assertEquals("ok", JsonParser.parseString(response.body())
                .getAsJsonObject().get("code").getAsString());
        assertEquals(QQ_ID, bridge.lastQqId);
        assertEquals("stop-token-1", bridge.lastStopId);
    }

    @Test
    void notifyResponseIncludesType() throws Exception {
        HttpResponse<String> response = get("/internal/qq/notify");

        assertEquals(200, response.statusCode());
        JsonObject item = JsonParser.parseString(response.body()).getAsJsonObject()
                .getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals(QqNotify.TYPE_DEVICE_APPROVAL, item.get("type").getAsString());
        assertEquals("approval-token-1", item.get("approvalId").getAsString());
    }

    private HttpResponse<String> post(String path, String body, boolean sign)
            throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (sign) {
            sign(builder, payload);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        sign(builder, new byte[0]);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void sign(HttpRequest.Builder builder, byte[] body) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = "nonce-" + nonceCounter.incrementAndGet() + "-" + UUID.randomUUID();
        builder.header("X-VA-Timestamp", timestamp);
        builder.header("X-VA-Nonce", nonce);
        builder.header("X-VA-Signature", hmac(timestamp, nonce, body));
    }

    private static String hmac(String timestamp, String nonce, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '\n');
        mac.update(nonce.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '\n');
        mac.update(body);
        return HexFormat.of().formatHex(mac.doFinal());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class FakeBridge implements QqAuthBridge {

        private String lastDecision;
        private String lastQqId;
        private String lastApprovalId;
        private String lastStopId;

        @Override
        public QqResult bind(String platform, String groupId, String qqId,
                             String code, String nickname) {
            return QqResult.OK;
        }

        @Override
        public QqResult confirmLogin(String platform, String groupId, String qqId, String code) {
            return QqResult.OK;
        }

        @Override
        public QqResult decideDeviceApproval(String platform, String groupId, String qqId,
                                            String decision, String approvalId) {
            this.lastQqId = qqId;
            this.lastDecision = decision;
            this.lastApprovalId = approvalId;
            return QqResult.OK;
        }

        @Override
        public QqResult stopAutoLogin(String platform, String groupId, String qqId,
                                      String stopId) {
            this.lastQqId = qqId;
            this.lastStopId = stopId;
            return QqResult.OK;
        }

        @Override
        public List<QqNotify> pollNotifications() {
            return List.of(new QqNotify(QQ_ID, "message", QqNotify.TYPE_DEVICE_APPROVAL,
                    "approval-token-1"));
        }
    }
}
