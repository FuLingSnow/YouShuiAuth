package net.rafalohaki.veloauth.qq;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.rafalohaki.veloauth.lifecycle.ConnectionLifecycleRegistry;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory "new device" approvals waiting for a Y/N reply in the bound QQ group.
 *
 * <p>One QQ identity owns at most one pending approval: a newer login replaces the older one so
 * a stale prompt can never approve a different connection. Every entry is bound to the concrete
 * connection generation captured when the prompt was issued, expires after
 * {@code qq.device-approval.timeout-seconds}, and is consumed exactly once.
 */
public final class QqDeviceApprovals {

    /** Hard cap; matches the other in-memory QQ caches. */
    private static final int MAX_PENDING = 10_000;

    private final Cache<String, PendingDeviceApproval> pending;
    private final Cache<UUID, String> activeKeys;
    /** Secondary index: opaque approvalId -> platform:qqId key. */
    private final Cache<String, String> approvalIdKeys;

    public QqDeviceApprovals(int ttlSeconds) {
        long ttlMillis = Math.max(1, ttlSeconds) * 1000L;
        this.pending = Caffeine.newBuilder()
                .maximumSize(MAX_PENDING)
                .expireAfterWrite(Duration.ofMillis(ttlMillis))
                .build();
        this.activeKeys = Caffeine.newBuilder()
                .maximumSize(MAX_PENDING)
                .expireAfterWrite(Duration.ofMillis(ttlMillis))
                .build();
        this.approvalIdKeys = Caffeine.newBuilder()
                .maximumSize(MAX_PENDING)
                .expireAfterWrite(Duration.ofMillis(ttlMillis))
                .build();
    }

    /**
     * Stores the approval, replacing any previous pending entry for the same QQ identity.
     *
     * @return the replaced entry, or empty when this QQ had no live approval
     */
    public Optional<PendingDeviceApproval> put(PendingDeviceApproval approval) {
        Objects.requireNonNull(approval, "approval must not be null");
        String key = key(approval.platform(), approval.qqId());
        PendingDeviceApproval previous = pending.asMap().put(key, approval);
        if (previous != null) {
            activeKeys.asMap().remove(previous.uuid(), key);
            approvalIdKeys.asMap().remove(previous.approvalId(), key);
        }
        activeKeys.put(approval.uuid(), key);
        approvalIdKeys.put(approval.approvalId(), key);
        return Optional.ofNullable(previous);
    }

    /**
     * Removes and returns the approval owned by this QQ identity.
     */
    public Optional<PendingDeviceApproval> consume(String platform, String qqId) {
        if (isBlank(platform) || isBlank(qqId)) {
            return Optional.empty();
        }
        String key = key(platform, qqId);
        PendingDeviceApproval removed = pending.asMap().remove(key);
        if (removed != null) {
            activeKeys.asMap().remove(removed.uuid(), key);
            approvalIdKeys.asMap().remove(removed.approvalId(), key);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * @return the live approval identified by this opaque approvalId without consuming it
     */
    public Optional<PendingDeviceApproval> peekByApprovalId(String approvalId) {
        String key = approvalIdKeys.getIfPresent(orEmpty(approvalId));
        return key == null ? Optional.empty() : Optional.ofNullable(pending.getIfPresent(key));
    }

    /**
     * Removes and returns the approval identified by this opaque approvalId. The reply route
     * uses it so the sender identity can be verified against the binding before consuming.
     */
    public Optional<PendingDeviceApproval> consumeByApprovalId(String approvalId) {
        if (isBlank(approvalId)) {
            return Optional.empty();
        }
        String key = approvalIdKeys.asMap().remove(approvalId.trim());
        if (key == null) {
            return Optional.empty();
        }
        PendingDeviceApproval removed = pending.asMap().remove(key);
        if (removed != null) {
            activeKeys.asMap().remove(removed.uuid(), key);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Removes and returns the approval waiting for this account, regardless of the QQ identity.
     * Used when the connection disappears or the account reconnects.
     */
    public Optional<PendingDeviceApproval> consumeByUuid(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        String key = activeKeys.asMap().remove(uuid);
        if (key == null) {
            return Optional.empty();
        }
        PendingDeviceApproval removed = pending.asMap().remove(key);
        if (removed != null && !removed.uuid().equals(uuid)) {
            // The key was already reused by a newer login for another account; put it back.
            pending.asMap().putIfAbsent(key, removed);
            activeKeys.asMap().putIfAbsent(removed.uuid(), key);
            return Optional.empty();
        }
        if (removed != null) {
            approvalIdKeys.asMap().remove(removed.approvalId(), key);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * @return the approval waiting for this account without consuming it, or empty when none
     */
    public Optional<PendingDeviceApproval> peekByUuid(UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        String key = activeKeys.getIfPresent(uuid);
        if (key == null) {
            return Optional.empty();
        }
        PendingDeviceApproval approval = pending.getIfPresent(key);
        return approval != null && approval.uuid().equals(uuid)
                ? Optional.of(approval)
                : Optional.empty();
    }

    /**
     * @return the live approval for this QQ identity without consuming it
     */
    public Optional<PendingDeviceApproval> peek(String platform, String qqId) {
        if (isBlank(platform) || isBlank(qqId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(pending.getIfPresent(key(platform, qqId)));
    }

    public void clear() {
        pending.invalidateAll();
        activeKeys.invalidateAll();
        approvalIdKeys.invalidateAll();
    }

    private static String key(String platform, String qqId) {
        return platform + ':' + qqId;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * One pending confirmation. {@code operation} proves the approving reply targets the live
     * connection instead of a reconnected player that reused the same UUID. {@code approvalId}
     * is an opaque random identifier delivered to the bot with the notification; quoting the
     * notification and replying Y/N sends it back, and the server verifies that the replier is
     * the bound QQ before consuming.
     */
    public record PendingDeviceApproval(
            String platform,
            String qqId,
            String approvalId,
            UUID uuid,
            ConnectionLifecycleRegistry.Operation operation,
            String fingerprint,
            long expiresAt) {

        public PendingDeviceApproval {
            Objects.requireNonNull(platform, "platform must not be null");
            Objects.requireNonNull(qqId, "qqId must not be null");
            if (approvalId == null || approvalId.isBlank()) {
                throw new IllegalArgumentException("approvalId must not be blank");
            }
            Objects.requireNonNull(uuid, "uuid must not be null");
        }

        public boolean isExpired(long now) {
            return expiresAt <= now;
        }
    }
}
