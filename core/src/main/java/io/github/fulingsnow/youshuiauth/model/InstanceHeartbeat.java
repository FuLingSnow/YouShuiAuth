package io.github.fulingsnow.youshuiauth.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

/**
 * 跨机房部署注册表：每个实例启动时 upsert 一行并定时刷新心跳，
 * 让运维能从任一实例查看当前共享数据库上有哪些活跃部署。2.0.0 会在此基础上加互斥锁。
 */
@DatabaseTable(tableName = "YOUSHUI_INSTANCE")
public class InstanceHeartbeat {

    @DatabaseField(columnName = "DEPLOYMENT_ID", id = true, canBeNull = false, width = 64)
    private String deploymentId;

    @DatabaseField(columnName = "PLATFORM", canBeNull = false, width = 16)
    private String platform;

    @DatabaseField(columnName = "VERSION", width = 16)
    private String version;

    @DatabaseField(columnName = "STARTED_AT", canBeNull = false)
    private long startedAt;

    @DatabaseField(columnName = "HEARTBEAT_AT", canBeNull = false)
    private long heartbeatAt;

    public InstanceHeartbeat() {
    }

    public InstanceHeartbeat(String deploymentId, String platform, String version,
                             long startedAt, long heartbeatAt) {
        this.deploymentId = deploymentId;
        this.platform = platform;
        this.version = version;
        this.startedAt = startedAt;
        this.heartbeatAt = heartbeatAt;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public String getPlatform() {
        return platform;
    }

    public String getVersion() {
        return version;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setHeartbeatAt(long heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }
}
