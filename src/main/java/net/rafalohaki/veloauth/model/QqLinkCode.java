package net.rafalohaki.veloauth.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.Objects;

/**
 * Single-use code that proves ownership of an account before a QQ identity is bound.
 *
 * <p>Only the SHA-256 hash of the code is stored, so a database leak cannot be replayed
 * against the bridge. {@code CONSUMED_AT} is the atomic consumption marker: a conditional
 * update wins exactly once.
 */
@DatabaseTable(tableName = "VELOAUTH_QQ_LINK_CODE")
public class QqLinkCode {

    public static final String PURPOSE_BIND = "bind";
    /** First-time QQ registration for a nickname that has no AUTH row yet. */
    public static final String PURPOSE_REGISTER = "register";

    @DatabaseField(columnName = "CODE_HASH", id = true, canBeNull = false, width = 64)
    private String codeHash;

    @DatabaseField(columnName = "UUID", canBeNull = false, width = 36)
    private String uuid;

    @DatabaseField(columnName = "PURPOSE", canBeNull = false, width = 16)
    private String purpose;

    @DatabaseField(columnName = "CREATED_AT", canBeNull = false)
    private long createdAt;

    @DatabaseField(columnName = "EXPIRES_AT", canBeNull = false)
    private long expiresAt;

    @DatabaseField(columnName = "CONSUMED_AT")
    private Long consumedAt;

    public QqLinkCode() {
        // ORMLite requires a no-arg constructor.
    }

    public QqLinkCode(String codeHash, String uuid, String purpose,
                      long createdAt, long expiresAt) {
        if (codeHash == null || codeHash.isBlank()) {
            throw new IllegalArgumentException("codeHash must not be blank");
        }
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("uuid must not be blank");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("purpose must not be blank");
        }
        this.codeHash = codeHash;
        this.uuid = uuid.trim();
        this.purpose = purpose.trim();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(long now) {
        return expiresAt <= now;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public String getUuid() {
        return uuid;
    }

    public String getPurpose() {
        return purpose;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public Long getConsumedAt() {
        return consumedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof QqLinkCode that && Objects.equals(codeHash, that.codeHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(codeHash);
    }

    @Override
    public String toString() {
        return "QqLinkCode{purpose='%s', uuid='%s', expiresAt=%d, consumed=%b}"
                .formatted(purpose, uuid, expiresAt, consumedAt != null);
    }
}
