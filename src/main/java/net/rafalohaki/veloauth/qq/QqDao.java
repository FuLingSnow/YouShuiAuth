package net.rafalohaki.veloauth.qq;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.stmt.DeleteBuilder;
import com.j256.ormlite.stmt.UpdateBuilder;
import com.j256.ormlite.support.ConnectionSource;
import net.rafalohaki.veloauth.model.QqBinding;
import net.rafalohaki.veloauth.model.QqLinkCode;
import net.rafalohaki.veloauth.model.QqRememberToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence for QQ bindings, one-time link codes and remember-device tokens.
 *
 * <p>Hot paths (bind confirmation, quick login) use primary-key or unique-index lookups only.
 * Codes and tokens are stored as SHA-256 hashes; plaintext never reaches the database.
 */
public class QqDao {

    private static final Logger logger = LoggerFactory.getLogger(QqDao.class);
    private static final Marker DB_MARKER = MarkerFactory.getMarker("DATABASE");

    private static final String COL_PLATFORM = "PLATFORM";
    private static final String COL_UUID = "UUID";
    private static final String COL_CODE_HASH = "CODE_HASH";
    private static final String COL_CONSUMED_AT = "CONSUMED_AT";
    private static final String COL_PURPOSE = "PURPOSE";
    private static final String COL_EXPIRES_AT = "EXPIRES_AT";
    private static final String COL_REVOKED = "REVOKED";

    private final Dao<QqBinding, String> bindingDao;
    private final Dao<QqLinkCode, String> linkCodeDao;
    private final Dao<QqRememberToken, String> rememberTokenDao;

    public QqDao(ConnectionSource connectionSource) throws SQLException {
        Objects.requireNonNull(connectionSource, "connectionSource must not be null");
        this.bindingDao = DaoManager.createDao(connectionSource, QqBinding.class);
        this.linkCodeDao = DaoManager.createDao(connectionSource, QqLinkCode.class);
        this.rememberTokenDao = DaoManager.createDao(connectionSource, QqRememberToken.class);
        logger.debug(DB_MARKER, "QqDao initialized");
    }

    // ===== Bindings =====

    public Optional<QqBinding> findByQq(String platform, String qqId) throws SQLException {
        if (isBlank(platform) || isBlank(qqId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(bindingDao.queryForId(QqBinding.key(platform, qqId)));
    }

    public Optional<QqBinding> findByUuid(String platform, String uuid) throws SQLException {
        if (isBlank(platform) || isBlank(uuid)) {
            return Optional.empty();
        }
        List<QqBinding> rows = bindingDao.queryBuilder()
                .where().eq(COL_PLATFORM, platform.trim()).and().eq(COL_UUID, uuid.trim())
                .query();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Inserts a binding. Unique indexes on {@code (PLATFORM, UUID)} and the synthetic
     * {@code BINDING_KEY} make concurrent double-binding fail instead of overwriting.
     *
     * @return the persisted binding
     * @throws SQLException on unique violation or database failure
     */
    public QqBinding createBinding(QqBinding binding) throws SQLException {
        Objects.requireNonNull(binding, "binding must not be null");
        bindingDao.create(binding);
        if (logger.isInfoEnabled()) {
            logger.info(DB_MARKER, "QQ binding created for UUID {} (platform={})",
                    binding.getUuid(), binding.getPlatform());
        }
        return binding;
    }

    /**
     * Returns the most recent bindings for operator inspection.
     */
    public List<QqBinding> listBindings(int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 500));
        return bindingDao.queryBuilder()
                .orderBy("BOUND_AT", false)
                .limit((long) bounded)
                .query();
    }

    public boolean deleteByQq(String platform, String qqId) throws SQLException {
        if (isBlank(platform) || isBlank(qqId)) {
            return false;
        }
        return bindingDao.deleteById(QqBinding.key(platform, qqId)) > 0;
    }

    public boolean deleteByUuid(String platform, String uuid) throws SQLException {
        if (isBlank(platform) || isBlank(uuid)) {
            return false;
        }
        DeleteBuilder<QqBinding, String> delete = bindingDao.deleteBuilder();
        delete.where().eq(COL_PLATFORM, platform.trim()).and().eq(COL_UUID, uuid.trim());
        return delete.delete() > 0;
    }

    // ===== One-time link codes =====

    public void insertLinkCode(QqLinkCode code) throws SQLException {
        Objects.requireNonNull(code, "code must not be null");
        linkCodeDao.create(code);
    }

    public Optional<QqLinkCode> findLinkCode(String codeHash) throws SQLException {
        if (isBlank(codeHash)) {
            return Optional.empty();
        }
        return Optional.ofNullable(linkCodeDao.queryForId(codeHash));
    }

    /**
     * Deletes every previous code for the same account/purpose. Called when issuing a new code
     * so an old plaintext code can never remain valid alongside the new one.
     */
    public int deleteLinkCodesByUuidAndPurpose(String uuid, String purpose) throws SQLException {
        if (isBlank(uuid) || isBlank(purpose)) {
            return 0;
        }
        DeleteBuilder<QqLinkCode, String> delete = linkCodeDao.deleteBuilder();
        delete.where()
                .eq(COL_UUID, uuid.trim())
                .and()
                .eq(COL_PURPOSE, purpose.trim());
        return delete.delete();
    }

    /**
     * Atomically marks a code as consumed.
     *
     * @return {@code true} only for the single caller that won the conditional update
     */
    public boolean consumeLinkCode(String codeHash, long now) throws SQLException {
        if (isBlank(codeHash)) {
            return false;
        }
        UpdateBuilder<QqLinkCode, String> update = linkCodeDao.updateBuilder();
        update.updateColumnValue(COL_CONSUMED_AT, now);
        update.where().eq(COL_CODE_HASH, codeHash).and().isNull(COL_CONSUMED_AT);
        return update.update() == 1;
    }

    public int deleteExpiredLinkCodes(long now) throws SQLException {
        DeleteBuilder<QqLinkCode, String> delete = linkCodeDao.deleteBuilder();
        delete.where().le(COL_EXPIRES_AT, now);
        return delete.delete();
    }

    // ===== Remember-device tokens =====

    public void insertRememberToken(QqRememberToken token) throws SQLException {
        Objects.requireNonNull(token, "token must not be null");
        rememberTokenDao.create(token);
    }

    /**
     * Deletes every device row of an account. Used by single-device semantics before inserting
     * the freshly authenticated device, so old rows cannot accumulate.
     */
    public int deleteRememberTokensByUuid(String uuid) throws SQLException {
        if (isBlank(uuid)) {
            return 0;
        }
        DeleteBuilder<QqRememberToken, String> delete = rememberTokenDao.deleteBuilder();
        delete.where().eq(COL_UUID, uuid.trim());
        return delete.delete();
    }

    public Optional<QqRememberToken> findRememberToken(String tokenHash) throws SQLException {
        if (isBlank(tokenHash)) {
            return Optional.empty();
        }
        return Optional.ofNullable(rememberTokenDao.queryForId(tokenHash));
    }

    /**
     * Returns a live device row for an account. Used to distinguish "first login on a new
     * account" from "known account logging in from a different device".
     */
    public Optional<QqRememberToken> findUsableRememberTokenByUuid(String uuid, long now)
            throws SQLException {
        if (isBlank(uuid)) {
            return Optional.empty();
        }
        List<QqRememberToken> rows = rememberTokenDao.queryBuilder()
                .where().eq(COL_UUID, uuid.trim())
                .and().eq(COL_REVOKED, false)
                .and().gt(COL_EXPIRES_AT, now)
                .query();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean touchRememberToken(QqRememberToken token) throws SQLException {
        if (token == null) {
            return false;
        }
        UpdateBuilder<QqRememberToken, String> update = rememberTokenDao.updateBuilder();
        update.updateColumnValue("LAST_SEEN", token.getLastSeen());
        update.where().eq("TOKEN_HASH", token.getTokenHash()).and().eq(COL_REVOKED, false);
        return update.update() == 1;
    }

    /**
     * Revokes every remember-device token of an account. Called on password change, unbind and
     * administrator action so a stolen cookie cannot outlive the credential it was issued for.
     */
    public int revokeAllForUuid(String uuid) throws SQLException {
        if (isBlank(uuid)) {
            return 0;
        }
        UpdateBuilder<QqRememberToken, String> update = rememberTokenDao.updateBuilder();
        update.updateColumnValue(COL_REVOKED, true);
        update.where().eq(COL_UUID, uuid.trim()).and().eq(COL_REVOKED, false);
        int revoked = update.update();
        if (revoked > 0 && logger.isInfoEnabled()) {
            logger.info(DB_MARKER, "Revoked {} remember-device token(s) for UUID {}", revoked, uuid);
        }
        return revoked;
    }

    public int purgeExpiredRememberTokens(long now) throws SQLException {
        DeleteBuilder<QqRememberToken, String> delete = rememberTokenDao.deleteBuilder();
        delete.where().le(COL_EXPIRES_AT, now);
        return delete.delete();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
