package io.github.fulingsnow.youshuiauth.database;

import com.j256.ormlite.dao.Dao;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import org.slf4j.Logger;
import org.slf4j.Marker;
import java.sql.SQLException;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * 凭据版本（credential epoch）读写：改密后让旧会话失效的计数。
 * 独立成类的原因：DAO 在连接建立前/关闭后为 null，
 * 这些分支不该混进认证主链路。
 */
final class CredentialEpochStore {

    private final Supplier<Dao<RegisteredPlayer, String>> daoSupplier;
    private final ExecutorService executor;
    private final Logger logger;
    private final Marker marker;

    CredentialEpochStore(Supplier<Dao<RegisteredPlayer, String>> daoSupplier,
                         ExecutorService executor, Logger logger, Marker marker) {
        this.daoSupplier = daoSupplier;
        this.executor = executor;
        this.logger = logger;
        this.marker = marker;
    }

    CompletableFuture<OptionalLong> find(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(OptionalLong.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            Dao<RegisteredPlayer, String> dao = daoSupplier.get();
            if (dao == null) {
                return OptionalLong.empty();
            }
            try {
                RegisteredPlayer player = dao.queryForFirst(
                        dao.queryBuilder().where().eq("UUID", playerId.toString()).prepare());
                return player == null ? OptionalLong.empty()
                        : OptionalLong.of(player.getCredentialEpoch());
            } catch (SQLException e) {
                logger.warn(marker, "读取凭据版本失败（{}）", playerId, e);
                return OptionalLong.empty();
            }
        }, executor);
    }

    CompletableFuture<Long> increment(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(0L);
        }
        return CompletableFuture.supplyAsync(() -> {
            Dao<RegisteredPlayer, String> dao = daoSupplier.get();
            if (dao == null) {
                return 0L;
            }
            try {
                RegisteredPlayer player = dao.queryForFirst(
                        dao.queryBuilder().where().eq("UUID", playerId.toString()).prepare());
                if (player == null) {
                    return 0L;
                }
                long next = player.getCredentialEpoch() + 1;
                player.setCredentialEpoch(next);
                dao.update(player);
                return next;
            } catch (SQLException e) {
                logger.warn(marker, "自增凭据版本失败（{}）", playerId, e);
                return -1L;
            }
        }, executor);
    }
}
