package io.github.fulingsnow.youshuiauth.alert;

import io.github.fulingsnow.youshuiauth.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Alert service for premium resolver failures.
 * Monitors failure rates and sends Discord alerts when thresholds exceeded.
 * Thread-safe implementation with configurable thresholds.
 * <p>
 * Wired via {@code YouShuiAuth.initializePremiumResolver()} when {@code alerts.enabled}
 * is true in {@code config.yml}; {@link PremiumResolverService#recordResolution} flows
 * through here on every API attempt.
 */
public class PremiumResolverAlertService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PremiumResolverAlertService.class);
    private static final Marker PREMIUM_MARKER = MarkerFactory.getMarker("PREMIUM");

    private final Settings.AlertSettings alertSettings;
    private final DiscordWebhookClient discordClient;
    private final ScheduledExecutorService scheduler;

    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> failuresByResolver = new ConcurrentHashMap<>();
    private final AtomicLong lastAlertTime = new AtomicLong(0);
    private final AtomicLong lastResetTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean alertInFlight = new AtomicBoolean(false);

    /**
     * Creates alert service.
     *
     * @param settings Plugin settings
     */
    public PremiumResolverAlertService(Settings settings) {
        this(Objects.requireNonNull(settings, "settings").getAlertSettings());
    }

    private PremiumResolverAlertService(Settings.AlertSettings alertSettings) {
        this(alertSettings, createDiscordClient(alertSettings));
    }

    PremiumResolverAlertService(Settings.AlertSettings alertSettings, DiscordWebhookClient discordClient) {
        this.alertSettings = Objects.requireNonNull(alertSettings, "alertSettings");
        this.discordClient = discordClient;

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "YouShuiAuth-AlertService");
            t.setDaemon(true);
            return t;
        });

        long intervalMinutes = Math.max(1L, alertSettings.getCheckIntervalMinutes());
        scheduler.scheduleAtFixedRate(this::resetMetrics, intervalMinutes, intervalMinutes,
                TimeUnit.MINUTES);

        logger.info(PREMIUM_MARKER, "Alert service initialized (Discord: {}, Check interval: {}min)",
                discordClient != null ? "enabled" : "disabled",
                alertSettings.getCheckIntervalMinutes());
    }

    private static DiscordWebhookClient createDiscordClient(Settings.AlertSettings settings) {
        if (!settings.isDiscordEnabled() || settings.getDiscordWebhookUrl() == null
                || settings.getDiscordWebhookUrl().isBlank()) {
            return null;
        }
        try {
            return new DiscordWebhookClient(settings.getDiscordWebhookUrl());
        } catch (IllegalArgumentException invalidUrl) {

            logger.warn(PREMIUM_MARKER, "Discord 告警已禁用：{}", invalidUrl.getMessage());
            return null;
        }
    }

    /**
     * Records a premium resolution attempt.
     *
     * @param resolver Resolver name (mojang, ashcon, wpme)
     * @param success  Whether resolution was successful
     */
    public void recordResolution(String resolver, boolean success) {
        if (!alertSettings.isEnabled()) {
            return;
        }

        totalRequests.incrementAndGet();

        if (!success) {
            failedRequests.incrementAndGet();
            failuresByResolver.computeIfAbsent(resolver, k -> new AtomicInteger(0)).incrementAndGet();

            checkAndSendAlert();
        }
    }

    /**
     * Checks failure rate and sends alert if threshold exceeded.
     */
    private void checkAndSendAlert() {
        int total = totalRequests.get();
        int failed = failedRequests.get();

        if (total < alertSettings.getMinRequestsForAlert()) {
            return;
        }

        double failureRate = (double) failed / total;

        if (failureRate < alertSettings.getFailureRateThreshold()) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastAlertTime.get();
        long cooldownMs = alertSettings.getAlertCooldownMinutes() * 60_000L;

        if (now - last < cooldownMs) {
            logger.debug(PREMIUM_MARKER, "Alert cooldown active, skipping ({}s remaining)",
                    (cooldownMs - (now - last)) / 1000);
            return;
        }

        if (!alertInFlight.compareAndSet(false, true)) {
            return;
        }

        try {
            scheduler.execute(() -> {
                try {
                    if (sendFailureAlert(total, failed, failureRate)) {
                        lastAlertTime.set(System.currentTimeMillis());
                    }
                } finally {
                    alertInFlight.set(false);
                }
            });
        } catch (RuntimeException e) {
            alertInFlight.set(false);
            logger.warn(PREMIUM_MARKER, "Failed to schedule premium resolver alert", e);
        }
    }

    /**
     * Sends failure alert to Discord.
     *
     * @param total       Total requests
     * @param failed      Failed requests
     * @param failureRate Failure rate (0.0-1.0)
     */
    private boolean sendFailureAlert(int total, int failed, double failureRate) {
        if (discordClient == null) {
            logLocalAlert(total, failed, failureRate);
            return true;
        }

        try {
            DiscordWebhookClient.DiscordEmbed embed = createFailureAlertEmbed(total, failed, failureRate);

            boolean sent = discordClient.sendEmbed(embed);
            if (sent) {
                logLocalAlert(total, failed, failureRate, "Premium resolver alert sent to Discord: {}/{} ({}%)");
            } else {
                logger.error(PREMIUM_MARKER, "Failed to send Discord alert (check webhook URL)");
            }
            return sent;

        } catch (Exception e) {
            logger.error(PREMIUM_MARKER, "Error sending Discord alert", e);
            return false;
        }
    }

    private void logLocalAlert(int total, int failed, double failureRate) {
        logLocalAlert(total, failed, failureRate, "⚠️ Premium resolver failure rate: {}/{} ({}%)");
    }

    private void logLocalAlert(int total, int failed, double failureRate, String template) {
        if (logger.isWarnEnabled()) {
            logger.warn(PREMIUM_MARKER, template,
                    failed, total, String.format("%.1f", failureRate * 100));
        }
    }

    private DiscordWebhookClient.DiscordEmbed createFailureAlertEmbed(int total, int failed, double failureRate) {
        return new DiscordWebhookClient.DiscordEmbed()
                .title("⚠️ YouShuiAuth Premium Resolver Alert")
                .description("High failure rate detected in premium resolver service")
                .color(0xFFA500)
                .timestamp(Instant.now().toString())
                .fields(java.util.List.of(
                        new DiscordWebhookClient.EmbedField(
                                "Failure Rate",
                                String.format("%.1f%% (%d/%d)", failureRate * 100, failed, total),
                                true
                        ),
                        new DiscordWebhookClient.EmbedField(
                                "Threshold",
                                String.format("%.1f%%", alertSettings.getFailureRateThreshold() * 100),
                                true
                        ),
                        new DiscordWebhookClient.EmbedField(
                                "Time Window",
                                String.format("%d minutes", alertSettings.getCheckIntervalMinutes()),
                                true
                        ),
                        new DiscordWebhookClient.EmbedField(
                                "Failures by Resolver",
                                buildFailureBreakdown(),
                                false
                        ),
                        new DiscordWebhookClient.EmbedField(
                                "Recommendation",
                                "Check resolver API status and network connectivity",
                                false
                        )
                ));
    }

    /**
     * Builds failure breakdown by resolver.
     *
     * @return Formatted string with failures per resolver
     */
    private String buildFailureBreakdown() {
        if (failuresByResolver.isEmpty()) {
            return "No data";
        }

        StringBuilder sb = new StringBuilder();
        failuresByResolver.forEach((resolver, count) ->
            sb.append("**").append(resolver).append("**: ").append(count.get()).append(" failures\n")
        );
        return sb.toString().trim();
    }

    /**
     * Resets metrics for new time window.
     */
    private void resetMetrics() {
        int previousTotal = totalRequests.getAndSet(0);
        int previousFailed = failedRequests.getAndSet(0);
        failuresByResolver.clear();
        lastResetTime.set(System.currentTimeMillis());

        if (previousTotal > 0 && logger.isDebugEnabled()) {
            double failureRate = (double) previousFailed / previousTotal;
            logger.debug(PREMIUM_MARKER, "Metrics reset: {}/{} requests failed ({}%)",
                    previousFailed, previousTotal, String.format("%.1f", failureRate * 100));
        }
    }

    /**
     * Gets current metrics for monitoring.
     *
     * @return Alert metrics
     */
    public AlertMetrics getMetrics() {
        int total = totalRequests.get();
        int failed = failedRequests.get();
        double failureRate = total > 0 ? (double) failed / total : 0.0;

        return new AlertMetrics(
                total,
                failed,
                failureRate,
                System.currentTimeMillis() - lastResetTime.get(),
                lastAlertTime.get()
        );
    }

    @Override
    public void close() {
        logger.info(PREMIUM_MARKER, "Shutting down alert service...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        if (discordClient != null) {
            discordClient.close();
        }
        logger.info(PREMIUM_MARKER, "Alert service shut down");
    }

    /**
     * Alert metrics data.
     */
    public record AlertMetrics(
            int totalRequests,
            int failedRequests,
            double failureRate,
            long windowAgeMs,
            long lastAlertTime
    ) {
    }
}
