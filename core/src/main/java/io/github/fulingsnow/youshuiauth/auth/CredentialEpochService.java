package io.github.fulingsnow.youshuiauth.auth;

import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 凭据版本守卫：改密/注销会递增 AUTH 表的 CREDENTIAL_EPOCH，共享同一库的其他部署
 * 通过定期比对发现落后并强制重新认证。进程内没有共享状态，这是 1.x 唯一可靠的跨端失效手段。
 */
public final class CredentialEpochService {

    /** 校验频率上限：每条命令都查库在跨机房部署下不可接受。 */
    private static final long REFRESH_INTERVAL_MS = 30_000L;

    private final long refreshIntervalMs;

    private static final Marker SECURITY_MARKER = MarkerFactory.getMarker("SECURITY");

    private final DatabaseManager databaseManager;
    private final AuthCache cache;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Long> knownEpochs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastCheckedAt = new ConcurrentHashMap<>();

    public CredentialEpochService(DatabaseManager databaseManager, AuthCache cache, Logger logger) {
        this(databaseManager, cache, logger, REFRESH_INTERVAL_MS);
    }

    CredentialEpochService(DatabaseManager databaseManager, AuthCache cache, Logger logger,
                           long refreshIntervalMs) {
        this.databaseManager = databaseManager;
        this.cache = cache;
        this.logger = logger;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /**
     * 记录登录成功时的版本基线。
     */
    public void record(UUID playerId, Long epoch) {
        if (playerId == null || epoch == null || epoch < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        knownEpochs.put(playerId, epoch);
        lastCheckedAt.put(playerId, now);
    }

    /**
     * 在凭据变更发生的部署上调用：递增库内版本并对齐本地记录。
     *
     * @return new epoch, or 0 when the bump could not be applied
     */
    public long bump(UUID playerId) {
        long next = databaseManager.incrementCredentialEpoch(playerId).join();
        if (next > 0) {
            knownEpochs.put(playerId, next);
            lastCheckedAt.put(playerId, System.currentTimeMillis());
        }
        return next;
    }

    /**
     * 判断该玩家的本地会话是否已落后于库内版本；落后时立即撤销本地授权。
     * 数据库不可达或账号不存在时按「未过期」处理，避免数据库抖动把全员踢下线。
     */
    public boolean isStale(UUID playerId) {
        Long known = knownEpochs.get(playerId);
        if (known == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long lastChecked = lastCheckedAt.get(playerId);
        if (lastChecked != null && now - lastChecked < refreshIntervalMs) {
            return false;
        }
        lastCheckedAt.put(playerId, now);
        long stored = databaseManager.findCredentialEpoch(playerId).join().orElse(-1);
        if (stored < 0 || stored == known) {
            return false;
        }
        logger.warn(SECURITY_MARKER, "Credential epoch advanced for {} ({} → {}); "
                + "revoking local session", playerId, known, stored);
        knownEpochs.put(playerId, stored);
        cache.invalidatePlayerData(playerId);
        cache.endSession(playerId);
        return true;
    }
}
