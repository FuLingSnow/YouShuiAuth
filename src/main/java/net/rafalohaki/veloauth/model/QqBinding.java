package net.rafalohaki.veloauth.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.Objects;

/**
 * One-to-one binding between a QQ identity and a Minecraft account UUID.
 *
 * <p>Identity is always the UUID: Bedrock nicknames keep their Floodgate prefix and Java
 * nicknames may change, so neither is a stable key. The synthetic {@code BINDING_KEY}
 * ({@code platform:qqId}) keeps the primary key portable across SQLite/MySQL/PostgreSQL
 * without relying on composite primary key support in every ORMLite dialect.
 */
@DatabaseTable(tableName = "VELOAUTH_QQ_BINDING")
public class QqBinding {

    private static final int MAX_PLATFORM_LENGTH = 32;
    private static final int MAX_QQ_ID_LENGTH = 64;
    private static final int MAX_NICKNAME_LENGTH = 32;
    private static final int MAX_VIA_LENGTH = 16;

    @DatabaseField(columnName = "BINDING_KEY", id = true, canBeNull = false, width = 100)
    private String bindingKey;

    @DatabaseField(columnName = "PLATFORM", canBeNull = false, width = MAX_PLATFORM_LENGTH)
    private String platform;

    @DatabaseField(columnName = "QQ_ID", canBeNull = false, width = MAX_QQ_ID_LENGTH)
    private String qqId;

    @DatabaseField(columnName = "UUID", canBeNull = false, width = 36)
    private String uuid;

    @DatabaseField(columnName = "NICKNAME_AT_BIND", width = MAX_NICKNAME_LENGTH)
    private String nicknameAtBind;

    @DatabaseField(columnName = "BOUND_AT", canBeNull = false)
    private long boundAt;

    @DatabaseField(columnName = "BOUND_VIA", width = MAX_VIA_LENGTH)
    private String boundVia;

    public QqBinding() {
        // ORMLite requires a no-arg constructor.
    }

    public QqBinding(String platform, String qqId, String uuid,
                     String nicknameAtBind, long boundAt, String boundVia) {
        this.platform = requireText(platform, "platform", MAX_PLATFORM_LENGTH);
        this.qqId = requireText(qqId, "qqId", MAX_QQ_ID_LENGTH);
        this.uuid = requireText(uuid, "uuid", 36);
        this.nicknameAtBind = truncate(nicknameAtBind, MAX_NICKNAME_LENGTH);
        this.boundAt = boundAt;
        this.boundVia = truncate(boundVia, MAX_VIA_LENGTH);
        this.bindingKey = key(platform, qqId);
    }

    public static String key(String platform, String qqId) {
        return requireText(platform, "platform", MAX_PLATFORM_LENGTH)
                + ':' + requireText(qqId, "qqId", MAX_QQ_ID_LENGTH);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("QQ binding " + field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(
                    "QQ binding " + field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    public String getBindingKey() {
        return bindingKey;
    }

    public String getPlatform() {
        return platform;
    }

    public String getQqId() {
        return qqId;
    }

    public String getUuid() {
        return uuid;
    }

    public String getNicknameAtBind() {
        return nicknameAtBind;
    }

    public long getBoundAt() {
        return boundAt;
    }

    public String getBoundVia() {
        return boundVia;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof QqBinding that && Objects.equals(bindingKey, that.bindingKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bindingKey);
    }

    @Override
    public String toString() {
        return "QqBinding{platform='%s', qqId='%s', uuid='%s', boundAt=%d}"
                .formatted(platform, maskQq(qqId), uuid, boundAt);
    }

    private static String maskQq(String qqId) {
        if (qqId == null || qqId.length() <= 4) {
            return "****";
        }
        return "****" + qqId.substring(qqId.length() - 4);
    }
}
