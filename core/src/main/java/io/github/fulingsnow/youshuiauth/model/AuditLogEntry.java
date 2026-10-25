package io.github.fulingsnow.youshuiauth.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import java.util.Objects;

/**
 * Single audit log row in YOUSHUI_AUDIT_LOG.
 * Captures authentication-relevant events for forensics and admin tooling.
 * Async-written via {@code AuditLogService}; rows expire after a configurable
 * retention window.
 */
@DatabaseTable(tableName = "YOUSHUI_AUDIT_LOG")
public class AuditLogEntry {

    /**
     * 主键由应用生成而不是数据库自增：表改名（AUTH→YOUSHUI_*）会留下指向旧序列名的
     * DEFAULT，一旦序列与表数据不同步，数据库自增就会撞主键并让整条审计写入失败。
     * 应用侧生成（毫秒时间戳 + 毫秒内序号）单调、并发安全，也跨方言一致。
     */
    @DatabaseField(columnName = "ID", id = true)
    private Long id;

    @DatabaseField(columnName = "EVENT_TYPE", canBeNull = false, width = 32)
    private String eventType;

    @DatabaseField(columnName = "PLAYER_LOWERCASE", width = 16)
    private String playerLowercase;

    @DatabaseField(columnName = "IP", width = 45)
    private String ip;

    @DatabaseField(columnName = "TIMESTAMP", canBeNull = false)
    private long timestamp;

    @DatabaseField(columnName = "DETAILS", width = 512)
    private String details;

    /** 毫秒内序号，保证同毫秒并发写入不撞主键。 */
    private static final java.util.concurrent.atomic.AtomicInteger IN_MILLIS_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final int SEQUENCE_RANGE = 1000;

    /** 单调递增的审计主键（远大于历史自增 id，避免与存量行冲突）。 */
    public static long nextId() {
        long millis = System.currentTimeMillis();
        int seq = IN_MILLIS_SEQUENCE.updateAndGet(v -> (v + 1) % SEQUENCE_RANGE);
        return millis * SEQUENCE_RANGE + seq;
    }

    public AuditLogEntry() {
    }

    public AuditLogEntry(String eventType, String playerLowercase, String ip, long timestamp, String details) {
        this.id = nextId();
        this.eventType = eventType;
        this.playerLowercase = playerLowercase;
        this.ip = ip;
        this.timestamp = timestamp;
        this.details = details;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPlayerLowercase() {
        return playerLowercase;
    }

    public void setPlayerLowercase(String playerLowercase) {
        this.playerLowercase = playerLowercase;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuditLogEntry that = (AuditLogEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
