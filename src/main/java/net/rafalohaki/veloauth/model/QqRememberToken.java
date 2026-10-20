package net.rafalohaki.veloauth.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.Objects;

/**
 * Revocable "remember this device" token issued as a Velocity cookie.
 *
 * <p>The cookie carries the raw token; only its SHA-256 hash is persisted. Rows are removed
 * when the token expires or is revoked (password change, unbind, administrator action).
 */
@DatabaseTable(tableName = "VELOAUTH_REMEMBER_TOKEN")
public class QqRememberToken {

    @DatabaseField(columnName = "TOKEN_HASH", id = true, canBeNull = false, width = 64)
    private String tokenHash;

    @DatabaseField(columnName = "UUID", canBeNull = false, width = 36)
    private String uuid;

    @DatabaseField(columnName = "CREATED_AT", canBeNull = false)
    private long createdAt;

    @DatabaseField(columnName = "EXPIRES_AT", canBeNull = false)
    private long expiresAt;

    @DatabaseField(columnName = "LAST_SEEN")
    private long lastSeen;

    @DatabaseField(columnName = "REVOKED", canBeNull = false, defaultValue = "false")
    private boolean revoked;

    public QqRememberToken() {
        // ORMLite requires a no-arg constructor.
    }

    public QqRememberToken(String tokenHash, String uuid,
                           long createdAt, long expiresAt) {
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("tokenHash must not be blank");
        }
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("uuid must not be blank");
        }
        this.tokenHash = tokenHash;
        this.uuid = uuid.trim();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastSeen = createdAt;
    }

    public boolean isUsable(long now) {
        return !revoked && expiresAt > now;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getUuid() {
        return uuid;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void markSeen(long now) {
        this.lastSeen = now;
    }

    public void revoke() {
        this.revoked = true;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof QqRememberToken that
                && Objects.equals(tokenHash, that.tokenHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tokenHash);
    }

    @Override
    public String toString() {
        return "QqRememberToken{uuid='%s', expiresAt=%d, revoked=%b}"
                .formatted(uuid, expiresAt, revoked);
    }
}
