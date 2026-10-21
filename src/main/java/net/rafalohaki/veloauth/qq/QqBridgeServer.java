package net.rafalohaki.veloauth.qq;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loopback-only HTTP endpoint used by the AstrBot plugin.
 *
 * <p>Security model: the listener binds to a single loopback address, every request carries an
 * HMAC-SHA256 signature over {@code timestamp + "\n" + nonce + "\n" + body}, the timestamp must
 * be within five minutes and every nonce is accepted once. The shared secret is never sent on
 * the wire and business failures are reported as HTTP 200 with a stable code so the bot does
 * not need to distinguish transport errors from expected rejections.
 */
public final class QqBridgeServer implements AutoCloseable {

    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private static final String HEADER_TIMESTAMP = "X-VA-Timestamp";
    private static final String HEADER_NONCE = "X-VA-Nonce";
    private static final String HEADER_SIGNATURE = "X-VA-Signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";
    private static final String PATH_BIND = "/internal/qq/bind";
    private static final String PATH_CONFIRM_LOGIN = "/internal/qq/confirm-login";
    private static final String PATH_DEVICE_APPROVAL = "/internal/qq/device-approval";
    private static final String PATH_STOP_AUTO_LOGIN = "/internal/qq/stop-auto-login";
    private static final String PATH_NOTIFY = "/internal/qq/notify";
    private static final int MAX_BODY_BYTES = 4096;
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final int MIN_NONCE_LENGTH = 8;
    private static final int MAX_NONCE_LENGTH = 128;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final int THREAD_POOL_SIZE = 2;

    private static final HexFormat HEX = HexFormat.of();

    private final QqSettings settings;
    private final QqAuthBridge bridge;
    private final Logger logger;
    private final Gson gson = new Gson();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final Cache<String, Boolean> nonces = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();
    private final Cache<String, AtomicInteger> attempts = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    private HttpServer server;
    private ExecutorService executor;

    public QqBridgeServer(QqSettings settings, QqAuthBridge bridge) {
        this(settings, bridge, LoggerFactory.getLogger(QqBridgeServer.class));
    }

    QqBridgeServer(QqSettings settings, QqAuthBridge bridge, Logger logger) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.bridge = Objects.requireNonNull(bridge, "bridge must not be null");
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
    }

    public void start() throws IOException {
        lifecycleLock.lock();
        try {
            if (server != null) {
                return;
            }
            InetAddress address = InetAddress.getByName(settings.getBindAddress());
            HttpServer candidate = HttpServer.create(
                    new InetSocketAddress(address, settings.getPort()), 0);
            ExecutorService candidateExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE,
                    runnable -> {
                        Thread thread = new Thread(runnable, "YouShuiAuth-QqBridge");
                        thread.setDaemon(true);
                        return thread;
                    });
            candidate.createContext(PATH_BIND, this::handleBind);
            candidate.createContext(PATH_CONFIRM_LOGIN, exchange -> handle(exchange,
                    (platform, groupId, qqId, code, nickname) ->
                            bridge.confirmLogin(platform, groupId, qqId, code)));
            candidate.createContext(PATH_DEVICE_APPROVAL, this::handleDeviceApproval);
            candidate.createContext(PATH_STOP_AUTO_LOGIN, this::handleStopAutoLogin);
            candidate.createContext(PATH_NOTIFY, this::handleNotify);
            candidate.setExecutor(candidateExecutor);
            candidate.start();
            this.server = candidate;
            this.executor = candidateExecutor;
            logger.info("QQ bridge listening on {}:{}",
                    settings.getBindAddress(), settings.getPort());
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (server != null) {
                server.stop(0);
                server = null;
            }
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            nonces.invalidateAll();
            attempts.invalidateAll();
            logger.debug("QQ bridge stopped");
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void handle(HttpExchange exchange, RequestHandler handler) throws IOException {
        runEndpoint(exchange, "bridge", target -> handleRequest(target, handler, false));
    }

    private void handleBind(HttpExchange exchange) throws IOException {
        runEndpoint(exchange, "bind", target -> handleRequest(target, bridge::bind, true));
    }

    /**
     * Wraps one endpoint body so every handler shares the same close-on-finish and
     * unexpected-error behaviour.
     */
    private void runEndpoint(HttpExchange exchange, String description, EndpointHandler handler)
            throws IOException {
        try (exchange) {
            try {
                handler.handle(exchange);
            } catch (RuntimeException e) {
                logger.error("Unexpected error handling QQ {} request", description, e);
                writeJson(exchange, HTTP_INTERNAL_ERROR, "error", false);
            }
        }
    }

    private void handleRequest(HttpExchange exchange, RequestHandler handler, boolean boundOnly)
            throws IOException {
        Envelope envelope = verifyEnvelope(exchange);
        if (envelope == null) {
            return;
        }
        if (boundOnly && bridge.isQqBound(envelope.platform(), envelope.qqId())) {
            writeResult(exchange, QqResult.ALREADY_BOUND);
            return;
        }
        if (!enforceAttemptLimit(exchange, envelope)) {
            return;
        }
        String code = stringOr(envelope.json(), "code", "");
        if (code.isBlank()) {
            writeJson(exchange, HTTP_BAD_REQUEST, "bad_request", false);
            return;
        }
        writeResult(exchange, handler.handle(envelope.platform(), envelope.groupId(),
                envelope.qqId(), code, stringOr(envelope.json(), "nickname", "")));
    }

    /**
     * Applies the checks every signed POST endpoint shares: method, signature, identity fields
     * and group allow-list.
     *
     * @return the verified envelope, or {@code null} after an error response was written
     */
    private Envelope verifyEnvelope(HttpExchange exchange) throws IOException {
        JsonObject json = readVerifiedJson(exchange);
        if (json == null) {
            return null;
        }
        String platform = stringOr(json, "platform", "qq");
        String groupId = stringOr(json, "groupId", "");
        String qqId = stringOr(json, "qqId", "");
        if (groupId.isBlank() || qqId.isBlank()) {
            writeJson(exchange, HTTP_BAD_REQUEST, "bad_request", false);
            return null;
        }
        if (!isGroupAllowed(groupId)) {
            writeResult(exchange, QqResult.GROUP_NOT_ALLOWED);
            return null;
        }
        return new Envelope(json, platform, groupId, qqId);
    }

    private boolean enforceAttemptLimit(HttpExchange exchange, Envelope envelope)
            throws IOException {
        if (allowAttempt(envelope.platform(), envelope.qqId())) {
            return true;
        }
        writeResult(exchange, QqResult.RATE_LIMITED);
        return false;
    }

    /** Verified request identity shared by the signed POST endpoints. */
    private record Envelope(JsonObject json, String platform, String groupId, String qqId) {
    }

    /**
     * Validates the transport envelope shared by every signed POST endpoint.
     *
     * @return the parsed JSON body, or {@code null} after an error response was written
     */
    private JsonObject readVerifiedJson(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, HTTP_METHOD_NOT_ALLOWED, "method_not_allowed", false);
            return null;
        }
        byte[] body = readBody(exchange.getRequestBody());
        if (body.length == 0) {
            writeJson(exchange, HTTP_BAD_REQUEST, "bad_request", false);
            return null;
        }
        if (!verifySignature(exchange, body)) {
            logger.warn(SECURITY_MARKER, "Rejected QQ bridge request with invalid signature");
            writeJson(exchange, HTTP_UNAUTHORIZED, "unauthorized", false);
            return null;
        }
        JsonObject json = parseObject(body);
        if (json == null) {
            writeJson(exchange, HTTP_BAD_REQUEST, "bad_request", false);
        }
        return json;
    }

    /**
     * POST {@code /internal/qq/device-approval}: applies the group reply for a pending
     * new-device login. Body: {@code platform, groupId, qqId, decision, approvalId} where
     * {@code approvalId} comes from the approval notification and {@code qqId} is the replier.
     */
    private void handleDeviceApproval(HttpExchange exchange) throws IOException {
        runEndpoint(exchange, "device approval", this::handleDeviceApprovalRequest);
    }

    private void handleDeviceApprovalRequest(HttpExchange exchange) throws IOException {
        Envelope envelope = verifyEnvelope(exchange);
        if (envelope == null) {
            return;
        }
        if (!enforceAttemptLimit(exchange, envelope)) {
            return;
        }
        String decision = stringOr(envelope.json(), "decision",
                stringOr(envelope.json(), "code", ""));
        String approvalId = stringOr(envelope.json(), "approvalId", "");
        if (decision.isBlank() || approvalId.isBlank()) {
            writeJson(exchange, HTTP_BAD_REQUEST, "bad_request", false);
            return;
        }
        writeResult(exchange, bridge.decideDeviceApproval(envelope.platform(),
                envelope.groupId(), envelope.qqId(), decision, approvalId));
    }

    /**
     * POST {@code /internal/qq/stop-auto-login}: applies the quote-reply {@code 阻止} for a
     * cookie auto-login notification. Body: {@code platform, groupId, qqId, stopId} where
     * {@code stopId} comes from the notification and {@code qqId} is the replier.
     */
    private void handleStopAutoLogin(HttpExchange exchange) throws IOException {
        runEndpoint(exchange, "stop-auto-login", this::handleStopAutoLoginRequest);
    }

    private void handleStopAutoLoginRequest(HttpExchange exchange) throws IOException {
        Envelope envelope = verifyEnvelope(exchange);
        if (envelope == null) {
            return;
        }
        if (!enforceAttemptLimit(exchange, envelope)) {
            return;
        }
        String stopId = stringOr(envelope.json(), "stopId",
                stringOr(envelope.json(), "approvalId", ""));
        if (stopId.isBlank()) {
            writeJson(exchange, HTTP_BAD_REQUEST, "bad_request", false);
            return;
        }
        writeResult(exchange, bridge.stopAutoLogin(envelope.platform(), envelope.groupId(),
                envelope.qqId(), stopId));
    }

    /**
     * GET {@code /internal/qq/notify}: the bot poller drains pending auto-login notifications.
     * Signature covers the empty body; nonce replay protection applies as usual.
     */
    private void handleNotify(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                handleNotifyRequest(exchange);
            } catch (RuntimeException e) {
                logger.error("Unexpected error handling QQ notify request", e);
                writeJson(exchange, HTTP_INTERNAL_ERROR, "error", false);
            }
        }
    }

    private void handleNotifyRequest(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, HTTP_METHOD_NOT_ALLOWED, "method_not_allowed", false);
            return;
        }
        if (!verifySignature(exchange, new byte[0])) {
            logger.warn(SECURITY_MARKER, "Rejected QQ notify request with invalid signature");
            writeJson(exchange, HTTP_UNAUTHORIZED, "unauthorized", false);
            return;
        }
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        for (var notify : bridge.pollNotifications()) {
            JsonObject item = new JsonObject();
            item.addProperty("qqId", notify.qqId());
            item.addProperty("message", notify.message());
            item.addProperty("type", notify.type());
            if (notify.approvalId() != null) {
                item.addProperty("approvalId", notify.approvalId());
            }
            items.add(item);
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("items", items);
        byte[] payload = gson.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private boolean isGroupAllowed(String groupId) {        // "*" is an explicit opt-in for local testing; the validator logs a warning for it.
        return settings.getAllowedGroups().contains("*")
                || settings.getAllowedGroups().contains(groupId);
    }

    private boolean allowAttempt(String platform, String qqId) {
        String key = platform + ':' + qqId;
        AtomicInteger counter = attempts.get(key, ignored -> new AtomicInteger());
        return counter.incrementAndGet() <= settings.getMaxAttemptsPerMinute();
    }

    private boolean verifySignature(HttpExchange exchange, byte[] body) {
        String timestamp = exchange.getRequestHeaders().getFirst(HEADER_TIMESTAMP);
        String nonce = exchange.getRequestHeaders().getFirst(HEADER_NONCE);
        String signature = exchange.getRequestHeaders().getFirst(HEADER_SIGNATURE);
        if (timestamp == null || nonce == null || signature == null) {
            return false;
        }
        if (nonce.length() < MIN_NONCE_LENGTH || nonce.length() > MAX_NONCE_LENGTH) {
            return false;
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - epochSeconds) > MAX_CLOCK_SKEW_SECONDS) {
            return false;
        }

        byte[] expected = hmac(timestamp, nonce, body);
        byte[] provided;
        try {
            provided = HEX.parseHex(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            return false;
        }
        // Replay protection: the first request wins, duplicates within the window are rejected.
        return nonces.asMap().putIfAbsent(nonce, Boolean.TRUE) == null;
    }

    private byte[] hmac(String timestamp, String nonce, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    settings.getSharedSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(nonce.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '\n');
            mac.update(body);
            return mac.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /**
     * Reads at most {@link #MAX_BODY_BYTES}. Oversized and empty bodies both return an empty
     * array, which the caller maps to HTTP 400.
     */
    private byte[] readBody(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    return new byte[0];
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private JsonObject parseObject(byte[] body) {
        try {
            return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String stringOr(JsonObject json, String key, String fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return json.get(key).getAsString().trim();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void writeResult(HttpExchange exchange, QqResult result) throws IOException {
        writeJson(exchange, 200, result.code(), result.isOk());
    }

    private void writeJson(HttpExchange exchange, int status, String code, boolean ok) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("ok", ok);
        response.addProperty("code", code);
        byte[] payload = gson.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    @FunctionalInterface
    private interface RequestHandler {
        QqResult handle(String platform, String groupId, String qqId, String code, String nickname);
    }

    @FunctionalInterface
    private interface EndpointHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
