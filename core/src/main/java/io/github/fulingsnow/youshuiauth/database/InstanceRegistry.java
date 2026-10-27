package io.github.fulingsnow.youshuiauth.database;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.support.ConnectionSource;
import io.github.fulingsnow.youshuiauth.model.InstanceHeartbeat;
import org.slf4j.Logger;
import org.slf4j.Marker;

import java.sql.SQLException;
import java.util.List;

/**
 * 实例心跳表的读写：与认证主链路无关的运维侧数据，
 * 单独成类以免把「实例发现」的分支混进认证写库逻辑。
 */
final class InstanceRegistry {

    /** 每日清理节流：心跳本身每分钟一次，没必要每次都算过期行。 */
    private static final java.util.concurrent.atomic.AtomicLong LAST_PRUNE =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long PRUNE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000;
    private static final long STALE_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final ConnectionSource connectionSource;
    private final Logger logger;
    private final Marker marker;

    InstanceRegistry(ConnectionSource connectionSource, Logger logger, Marker marker) {
        this.connectionSource = connectionSource;
        this.logger = logger;
        this.marker = marker;
    }

    void upsert(String deploymentId, String platform, String version) {
        if (deploymentId == null || deploymentId.isBlank()) {
            return;
        }
        try {
            Dao<InstanceHeartbeat, String> dao =
                    DaoManager.createDao(connectionSource, InstanceHeartbeat.class);
            long now = System.currentTimeMillis();
            InstanceHeartbeat heartbeat = dao.queryForId(deploymentId);
            if (heartbeat == null) {
                heartbeat = new InstanceHeartbeat(deploymentId, platform, version, now, now);
            }
            heartbeat.setPlatform(platform);
            heartbeat.setVersion(version);
            heartbeat.setHeartbeatAt(now);
            dao.createOrUpdate(heartbeat);
            pruneIfDue(dao, now);
        } catch (SQLException e) {
            logger.warn(marker, "写入实例心跳失败（{}）", deploymentId);
        }
    }

    /** 换过 deployment.id 的旧行会永远显示为 stale，按天清一次即可。 */
    private void pruneIfDue(Dao<InstanceHeartbeat, String> dao, long now) {
        long last = LAST_PRUNE.get();
        if (now - last < PRUNE_INTERVAL_MILLIS || !LAST_PRUNE.compareAndSet(last, now)) {
            return;
        }
        int removed = pruneStale(dao, now - STALE_RETENTION_MILLIS);
        if (removed > 0) {
            logger.info(marker, "清理过期实例记录 {} 条", removed);
        }
    }

    private int pruneStale(Dao<InstanceHeartbeat, String> dao, long cutoff) {
        try {
            com.j256.ormlite.stmt.DeleteBuilder<InstanceHeartbeat, String> delete =
                    dao.deleteBuilder();
            delete.where().lt("HEARTBEAT_AT", cutoff);
            return delete.delete();
        } catch (SQLException e) {
            logger.warn(marker, "清理过期实例心跳失败", e);
            return 0;
        }
    }

    List<InstanceHeartbeat> list() {
        try {
            Dao<InstanceHeartbeat, String> dao =
                    DaoManager.createDao(connectionSource, InstanceHeartbeat.class);
            return dao.queryForAll();
        } catch (SQLException e) {
            logger.warn(marker, "读取实例心跳失败");
            return List.of();
        }
    }
}
