package io.github.fulingsnow.youshuiauth.database;

import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.support.DatabaseConnection;
import com.j256.ormlite.table.TableUtils;
import io.github.fulingsnow.youshuiauth.model.AuditLogEntry;
import io.github.fulingsnow.youshuiauth.model.PremiumUuid;
import io.github.fulingsnow.youshuiauth.model.QqBinding;
import io.github.fulingsnow.youshuiauth.model.QqLinkCode;
import io.github.fulingsnow.youshuiauth.model.QqRememberToken;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;
import io.github.fulingsnow.youshuiauth.model.SchemaVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Handles database schema creation, migration, and index management.
 * Extracted from DatabaseManager for single-responsibility.
 */
class DatabaseMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigrationService.class);
    private static final Marker DB_MARKER = MarkerFactory.getMarker("DATABASE");

    private static final String ALTER_TABLE = "ALTER TABLE ";
    private static final String ADD_COLUMN = " ADD COLUMN ";
    private static final String AUTH_TABLE = "YOUSHUI_AUTH";
    private static final String PREMIUM_UUIDS_TABLE = "YOUSHUI_PREMIUM_UUIDS";
    private static final String AUDIT_LOG_TABLE = "YOUSHUI_AUDIT_LOG";
    private static final String QQ_BINDING_TABLE = "YOUSHUI_QQ_BINDING";
    private static final String QQ_LINK_CODE_TABLE = "YOUSHUI_QQ_LINK_CODE";
    private static final String REMEMBER_TOKEN_TABLE = "YOUSHUI_REMEMBER_TOKEN";
    private static final String CREATE_SEQUENCE = "CREATE SEQUENCE ";
    private static final String CREATE_SEQUENCE_IF_NOT_EXISTS = "CREATE SEQUENCE IF NOT EXISTS ";
    private static final String CREATE_TABLE = "CREATE TABLE ";
    private static final String CREATE_TABLE_IF_NOT_EXISTS = "CREATE TABLE IF NOT EXISTS ";
    private static final String COLUMN_PREMIUM_UUID = "PREMIUMUUID";
    private static final String COLUMN_PRESERVE_UUID = "PRESERVE_UUID";
    private static final String COLUMN_CREDENTIAL_EPOCH = "CREDENTIAL_EPOCH";
    private static final int MIGRATION_LOCK_TIMEOUT_SECONDS = 30;
    private static final String MIGRATION_LOCK_NAME = "youshuiauth_migrate";

    private final DatabaseConfig config;

    DatabaseMigrationService(DatabaseConfig config) {
        this.config = config;
    }

    /**
     * Creates tables if they don't exist and migrates schema for limboauth compatibility.
     */
    public MigrationResult createTablesAndMigrate(ConnectionSource connectionSource, String createTablesMsg,
                                                   String tablesCreatedMsg) throws SQLException {
        if (logger.isDebugEnabled()) {
            logger.debug(createTablesMsg);
        }

        DatabaseType type = DatabaseType.fromName(config.getStorageType());
        if (type != DatabaseType.MYSQL && type != DatabaseType.POSTGRESQL) {
            return doCreateTablesAndMigrate(connectionSource, tablesCreatedMsg);
        }

        DatabaseConnection lockConnection = connectionSource.getReadWriteConnection(null);
        try {
            String acquireSql = type == DatabaseType.MYSQL
                    ? "SELECT GET_LOCK('" + MIGRATION_LOCK_NAME + "', 30)"
                    : "SELECT pg_advisory_lock(hashtext('" + MIGRATION_LOCK_NAME + "')::bigint)";
            boolean locked = executeLockStatement(lockConnection, acquireSql, "acquire",
                    type == DatabaseType.POSTGRESQL);
            try {
                return doCreateTablesAndMigrate(connectionSource, tablesCreatedMsg);
            } finally {
                if (locked) {
                    String releaseSql = type == DatabaseType.MYSQL
                            ? "SELECT RELEASE_LOCK('" + MIGRATION_LOCK_NAME + "')"
                            : "SELECT pg_advisory_unlock(hashtext('" + MIGRATION_LOCK_NAME + "')::bigint)";
                    executeLockStatement(lockConnection, releaseSql, "release",
                            type == DatabaseType.POSTGRESQL);
                }
            }
        } finally {
            connectionSource.releaseConnection(lockConnection);
        }
    }

    private MigrationResult doCreateTablesAndMigrate(ConnectionSource connectionSource,
                                                     String tablesCreatedMsg)
            throws SQLException {
        renameLegacyTables(connectionSource);
        createTablesQuietly(connectionSource);

        MigrationResult migrationResult = migrateAuthTableForLimboauth(connectionSource);
        createIndexesIfNotExists(connectionSource);

        if (logger.isDebugEnabled()) {
            logger.debug(tablesCreatedMsg);
        }
        return migrationResult;
    }

    /**
     * 跨机房多实例同时启动会并发执行 ALTER TABLE，即使幂等也可能互相报错；
     * MySQL 用 GET_LOCK、PostgreSQL 用会话级 advisory lock，本地库（H2/SQLite）单实例无需此锁。
     * 锁获取超时时降级为警告继续：DDL 本身幂等，锁只是缩小竞争窗口。
     *
     * PostgreSQL 的 pg_advisory_lock/unlock 返回 void（部分版本返回 boolean），
     * 不能按整数列读取；MySQL 的 GET_LOCK/RELEASE_LOCK 返回 1/0/null。
     */
    @SuppressWarnings({"java:S2077", "java:S2695"})
    private boolean executeLockStatement(DatabaseConnection connection, String sql, String action,
                                         boolean postgres) throws SQLException {
        try {
            java.sql.Connection jdbc = connection.getUnderlyingConnection();
            try (java.sql.PreparedStatement statement = jdbc.prepareStatement(sql)) {
                if (postgres) {
                    statement.setQueryTimeout(MIGRATION_LOCK_TIMEOUT_SECONDS);
                    statement.execute();
                    return true;
                }
                try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                    boolean ok = resultSet.next() && resultSet.getInt(1) == 1;
                    if (!ok && logger.isWarnEnabled()) {
                        logger.warn(DB_MARKER, "获取迁移锁 {} 超时；继续执行（DDL 本身幂等）", action);
                    }
                    return ok;
                }
            }
        } catch (SQLException e) {
            logger.warn(DB_MARKER, "迁移锁 {} 失败：{}", action, e.getMessage());
            return false;
        }
    }

    private void createTablesQuietly(ConnectionSource connectionSource) throws SQLException {
        try {

            com.j256.ormlite.logger.Logger.setGlobalLogLevel(com.j256.ormlite.logger.Level.WARNING);

            TableUtils.createTableIfNotExists(connectionSource, RegisteredPlayer.class);
            TableUtils.createTableIfNotExists(connectionSource, PremiumUuid.class);
            TableUtils.createTableIfNotExists(connectionSource, SchemaVersion.class);
            createAuditLogTableIfMissing(connectionSource);
            TableUtils.createTableIfNotExists(connectionSource, QqBinding.class);
            TableUtils.createTableIfNotExists(connectionSource, QqLinkCode.class);
            TableUtils.createTableIfNotExists(connectionSource, QqRememberToken.class);
            TableUtils.createTableIfNotExists(connectionSource,
                    io.github.fulingsnow.youshuiauth.model.InstanceHeartbeat.class);

            if (logger.isDebugEnabled()) {
                logger.debug(DB_MARKER, "Tables verified (CREATE TABLE IF NOT EXISTS)");
            }
        } finally {

            com.j256.ormlite.logger.Logger.setGlobalLogLevel(null);
        }
    }

    private void createAuditLogTableIfMissing(ConnectionSource connectionSource) throws SQLException {
        if (DatabaseType.fromName(config.getStorageType()) != DatabaseType.POSTGRESQL) {
            TableUtils.createTableIfNotExists(connectionSource, AuditLogEntry.class);
            return;
        }
        createPostgresAuditLogTableIfMissing(connectionSource);
    }

    private void createPostgresAuditLogTableIfMissing(ConnectionSource connectionSource) throws SQLException {
        DatabaseConnection connection = connectionSource.getReadWriteConnection(AUDIT_LOG_TABLE);
        try {
            List<String> statements = TableUtils.getCreateTableStatements(connectionSource, AuditLogEntry.class);
            for (String statement : statements) {
                connection.executeStatement(
                        postgresCreateStatementIfNotExists(statement),
                        DatabaseConnection.DEFAULT_RESULT_FLAGS);
            }
            if (logger.isDebugEnabled()) {
                logger.debug(DB_MARKER, "PostgreSQL audit log table verified with idempotent sequence creation");
            }
        } finally {
            connectionSource.releaseConnection(connection);
        }
    }

    static String postgresCreateStatementIfNotExists(String statement) {
        if (statement.startsWith(CREATE_SEQUENCE)
                && !statement.startsWith(CREATE_SEQUENCE_IF_NOT_EXISTS)) {
            return CREATE_SEQUENCE_IF_NOT_EXISTS + statement.substring(CREATE_SEQUENCE.length());
        }
        if (statement.startsWith(CREATE_TABLE)
                && !statement.startsWith(CREATE_TABLE_IF_NOT_EXISTS)) {
            return CREATE_TABLE_IF_NOT_EXISTS + statement.substring(CREATE_TABLE.length());
        }
        return statement;
    }

    private MigrationResult migrateAuthTableForLimboauth(ConnectionSource connectionSource) throws SQLException {
        DatabaseConnection dbConnection = connectionSource.getReadWriteConnection(null);
        try {
            return performColumnMigration(dbConnection);
        } finally {
            connectionSource.releaseConnection(dbConnection);
        }
    }

    private MigrationResult performColumnMigration(DatabaseConnection dbConnection) throws SQLException {
        java.sql.Connection connection = dbConnection.getUnderlyingConnection();
        ColumnMigrationResult migrationResult = checkExistingColumns(connection);
        DatabaseType dbType = DatabaseType.fromName(config.getStorageType());
        String quote = identifierQuote(dbType);

        addMissingColumns(connection, migrationResult, quote);
        MigrationResult legacyUuidMigration = markLegacyPremiumRowsForUuidPreservation(connection, quote);
        logMigrationComplete(migrationResult);
        return legacyUuidMigration;
    }

    @SuppressWarnings("java:S2077")
    private MigrationResult markLegacyPremiumRowsForUuidPreservation(java.sql.Connection connection,
                                                                      String quote) throws SQLException {
        String authTable = quoteIdentifier(quote, AUTH_TABLE);
        String preserveUuid = quoteIdentifier(quote, COLUMN_PRESERVE_UUID);
        String hash = quoteIdentifier(quote, "HASH");
        String premiumUuid = quoteIdentifier(quote, COLUMN_PREMIUM_UUID);
        String uuid = quoteIdentifier(quote, "UUID");
        String predicate = "(" + hash + " IS NULL OR TRIM(" + hash + ") = ?)"
                + " AND (" + preserveUuid + " IS NULL OR " + preserveUuid + " = ?)"
                + " AND (" + premiumUuid + " IS NULL OR " + premiumUuid + " = " + uuid + ")";
        int candidates = countLegacyPremiumUuidCandidates(connection, authTable, predicate);
        String sql = "UPDATE " + authTable + " SET " + preserveUuid + " = ? WHERE " + predicate;
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, true);
            statement.setString(2, "");
            statement.setBoolean(3, false);
            return new MigrationResult(candidates, statement.executeUpdate());
        }
    }

    @SuppressWarnings({"java:S2077", "java:S2695"})
    private int countLegacyPremiumUuidCandidates(java.sql.Connection connection, String authTable,
                                                   String predicate) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + authTable + " WHERE " + predicate;
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "");
            statement.setBoolean(2, false);
            try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Legacy UUID candidate count returned no row");
                }
                return resultSet.getInt(1);
            }
        }
    }

    private ColumnMigrationResult checkExistingColumns(java.sql.Connection connection) throws SQLException {
        boolean hasPremiumUuid = columnExists(connection, AUTH_TABLE, COLUMN_PREMIUM_UUID);
        boolean hasTotpToken = columnExists(connection, AUTH_TABLE, "TOTPTOKEN");
        boolean hasIssuedTime = columnExists(connection, AUTH_TABLE, "ISSUEDTIME");
        boolean hasConflictMode = columnExists(connection, AUTH_TABLE, "CONFLICT_MODE");
        boolean hasConflictTimestamp = columnExists(connection, AUTH_TABLE, "CONFLICT_TIMESTAMP");
        boolean hasOriginalNickname = columnExists(connection, AUTH_TABLE, "ORIGINAL_NICKNAME");
        boolean hasPreserveUuid = columnExists(connection, AUTH_TABLE, COLUMN_PRESERVE_UUID);
        boolean hasCredentialEpoch = columnExists(connection, AUTH_TABLE, COLUMN_CREDENTIAL_EPOCH);
        return new ColumnMigrationResult(hasPremiumUuid, hasTotpToken, hasIssuedTime, hasConflictMode,
                hasConflictTimestamp, hasOriginalNickname, hasPreserveUuid, hasCredentialEpoch);
    }

    private void addMissingColumns(java.sql.Connection connection, ColumnMigrationResult result, String quote) throws SQLException {
        if (!result.hasPremiumUuid) {
            addColumn(connection, quote, COLUMN_PREMIUM_UUID, "VARCHAR(36)", "Added column " + COLUMN_PREMIUM_UUID + " to AUTH table");
        }
        if (!result.hasTotpToken) {
            addColumn(connection, quote, "TOTPTOKEN", "VARCHAR(32)", "Added column TOTPTOKEN to AUTH table");
        }
        if (!result.hasIssuedTime) {
            addColumn(connection, quote, "ISSUEDTIME", "BIGINT DEFAULT 0", "Added column ISSUEDTIME to AUTH table");
        }
        if (!result.hasConflictMode) {
            addColumn(connection, quote, "CONFLICT_MODE", "BOOLEAN DEFAULT FALSE", "Added column CONFLICT_MODE to AUTH table");
        }
        if (!result.hasConflictTimestamp) {
            addColumn(connection, quote, "CONFLICT_TIMESTAMP", "BIGINT DEFAULT 0", "Added column CONFLICT_TIMESTAMP to AUTH table");
        }
        if (!result.hasOriginalNickname) {
            addColumn(connection, quote, "ORIGINAL_NICKNAME", "VARCHAR(16)", "Added column ORIGINAL_NICKNAME to AUTH table");
        }
        if (!result.hasPreserveUuid) {
            addColumn(connection, quote, COLUMN_PRESERVE_UUID, "BOOLEAN DEFAULT FALSE",
                    "Added column " + COLUMN_PRESERVE_UUID + " to AUTH table");
        }
        if (!result.hasCredentialEpoch) {
            addColumn(connection, quote, COLUMN_CREDENTIAL_EPOCH, "BIGINT DEFAULT 0",
                    "Added column " + COLUMN_CREDENTIAL_EPOCH + " to AUTH table");
        }
        if (!columnExists(connection, REMEMBER_TOKEN_TABLE, "PLATFORM")) {
            addColumn(connection, quote, REMEMBER_TOKEN_TABLE, "PLATFORM",
                    "VARCHAR(16) DEFAULT 'velocity'",
                    "Added column PLATFORM to REMEMBER_TOKEN table");
        }
    }

    private void addColumn(java.sql.Connection connection, String quote, String columnName, String columnDefinition, String logMessage) throws SQLException {
        addColumn(connection, quote, AUTH_TABLE, columnName, columnDefinition, logMessage);
    }

    private void addColumn(java.sql.Connection connection, String quote, String table,
                           String columnName, String columnDefinition, String logMessage) throws SQLException {
        String sql = ALTER_TABLE + quote + table + quote + ADD_COLUMN + quote + columnName + quote + " " + columnDefinition;
        try {
            executeAlterTable(connection, sql);
            if (logger.isInfoEnabled()) {
                logger.info(DB_MARKER, logMessage);
            }
        } catch (SQLException e) {
            String normalizedMessage = normalizeSqlMessage(e);
            if (e.getErrorCode() == 42121
                    || normalizedMessage.contains("duplicate column")
                    || normalizedMessage.contains("already exists")) {
                if (logger.isDebugEnabled()) {
                    logger.debug(DB_MARKER, "Column {} already exists in table {} - skipping (expected behavior)",
                              columnName, AUTH_TABLE);
                }
            } else {
                throw e;
            }
        }
    }

    private void logMigrationComplete(ColumnMigrationResult result) {
        if (logger.isDebugEnabled() && (!result.hasPremiumUuid || !result.hasTotpToken || !result.hasIssuedTime
                || !result.hasConflictMode || !result.hasConflictTimestamp || !result.hasOriginalNickname
                || !result.hasPreserveUuid)) {
            logger.debug(DB_MARKER, "AUTH schema migration for limboauth completed");
        }
    }

    @SuppressWarnings("java:S2077")
    private void executeAlterTable(java.sql.Connection connection, String sql) throws SQLException {
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static final String[][] LEGACY_TABLE_RENAMES = {
            {"AUTH", "YOUSHUI_AUTH"},
            {"VELOAUTH_AUTH", "YOUSHUI_AUTH"},
            {"PREMIUM_UUIDS", "YOUSHUI_PREMIUM_UUIDS"},
            {"VELOAUTH_PREMIUM_UUIDS", "YOUSHUI_PREMIUM_UUIDS"},
            {"VELOAUTH_AUDIT_LOG", "YOUSHUI_AUDIT_LOG"},
            {"VELOAUTH_QQ_BINDING", "YOUSHUI_QQ_BINDING"},
            {"VELOAUTH_QQ_LINK_CODE", "YOUSHUI_QQ_LINK_CODE"},
            {"VELOAUTH_REMEMBER_TOKEN", "YOUSHUI_REMEMBER_TOKEN"},
            {"VELOAUTH_INSTANCE", "YOUSHUI_INSTANCE"},
            {"VELOAUTH_SCHEMA_VERSION", "YOUSHUI_SCHEMA_VERSION"},
    };

    /**
     * 1.x → 2.0.0 表名迁移：旧表存在且新表不存在时原地改名，保留全部数据。
     * 必须在建表之前执行——先建新表会导致改名被跳过（数据留在旧表里）。
     */
    private void renameLegacyTables(ConnectionSource connectionSource) throws SQLException {
        DatabaseType type = DatabaseType.fromName(config.getStorageType());
        String quote = identifierQuote(type);
        DatabaseConnection pooledConnection = connectionSource.getReadWriteConnection(null);
        try {
            java.sql.Connection connection = pooledConnection.getUnderlyingConnection();
            java.util.Map<String, String> existingTables = new java.util.HashMap<>();
            try (java.sql.ResultSet tables = connection.getMetaData()
                    .getTables(null, null, null, null)) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (name != null) {
                        existingTables.put(name.toUpperCase(java.util.Locale.ROOT), name);
                    }
                }
            }
            for (String[] rename : LEGACY_TABLE_RENAMES) {
                String existingOld = existingTables.get(
                        rename[0].toUpperCase(java.util.Locale.ROOT));
                if (existingOld == null || existingTables.containsKey(rename[1])) {
                    continue;
                }
                if (renameTable(connection, type, quote, existingOld, rename[1])) {
                    logger.info(DB_MARKER, "Renamed legacy table {} to {}", existingOld, rename[1]);
                } else {

                    throw new SQLException("Cannot rename legacy table " + existingOld
                            + " to " + rename[1] + ": the database account needs ALTER/RENAME "
                            + "privilege on this schema. Grant it, or rename the tables manually, "
                            + "then restart the server.");
                }
            }
        } finally {
            connectionSource.releaseConnection(pooledConnection);
        }
    }

    /**
     * 表名大小写在各方言下折叠规则不同（PG 引号建表保留大写、H2/MySQL 折叠小写），
     * 依次尝试带引号与不带引号两种写法，成功后即返回。
     */
    private boolean renameTable(java.sql.Connection connection, DatabaseType type, String quote,
                                String existingOld, String targetName) {
        String[] oldRefs = {quoteIdentifier(quote, existingOld), existingOld};
        String[] newRefs = {quoteIdentifier(quote, targetName), targetName};
        for (String oldRef : oldRefs) {
            for (String newRef : newRefs) {
                String sql = type == DatabaseType.MYSQL
                        ? "RENAME TABLE " + oldRef + " TO " + newRef
                        : ALTER_TABLE + oldRef + " RENAME TO " + newRef;
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                    return true;
                } catch (SQLException ignored) {

                }
            }
        }
        return false;
    }

    private boolean columnExists(java.sql.Connection connection, String tableName, String columnName) throws SQLException {
        java.sql.DatabaseMetaData metaData = connection.getMetaData();
        MetadataScope scope = metadataScope(connection);
        String resolvedTableName = findTableName(metaData, scope, tableName);
        if (resolvedTableName == null) {
            return false;
        }

        try (java.sql.ResultSet columns = metaData.getColumns(
                scope.catalog(), scope.schema(), resolvedTableName, null)) {
            while (columns.next()) {
                String existingColumn = columns.getString("COLUMN_NAME");
                if (existingColumn != null && existingColumn.equalsIgnoreCase(columnName)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(DB_MARKER, "Column {} exists in table {} (found as: {})",
                                   columnName, tableName, existingColumn);
                    }
                    return true;
                }
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug(DB_MARKER, "Column {} does not exist in table {}", columnName, tableName);
        }
        return false;
    }

    private String findTableName(java.sql.DatabaseMetaData metaData, MetadataScope scope,
                                 String tableName) throws SQLException {
        try (java.sql.ResultSet tables = metaData.getTables(
                scope.catalog(), scope.schema(), null, null)) {
            while (tables.next()) {
                String existingTable = tables.getString("TABLE_NAME");
                if (existingTable != null && existingTable.equalsIgnoreCase(tableName)) {
                    return existingTable;
                }
            }
        }
        return null;
    }

    private MetadataScope metadataScope(java.sql.Connection connection) throws SQLException {
        return new MetadataScope(connection.getCatalog(), connection.getSchema());
    }

    private void createIndexesIfNotExists(ConnectionSource connectionSource) {
        DatabaseType dbType = DatabaseType.fromName(config.getStorageType());
        String quote = identifierQuote(dbType);
        createIndexIfMissing(connectionSource, AUTH_TABLE, "idx_auth_ip",
                buildCreateIndexSql(quote, "idx_auth_ip", AUTH_TABLE, "IP"));
        createIndexIfMissing(connectionSource, AUTH_TABLE, "idx_auth_uuid",
                buildCreateIndexSql(quote, "idx_auth_uuid", AUTH_TABLE, "UUID"));
        createIndexIfMissing(connectionSource, AUTH_TABLE, "idx_auth_logindate",
                buildCreateIndexSql(quote, "idx_auth_logindate", AUTH_TABLE, "LOGINDATE"));
        createIndexIfMissing(connectionSource, AUTH_TABLE, "idx_auth_regdate",
                buildCreateIndexSql(quote, "idx_auth_regdate", AUTH_TABLE, "REGDATE"));
        createIndexIfMissing(connectionSource, PREMIUM_UUIDS_TABLE, "idx_premium_uuids_nickname",
                buildCreateIndexSql(quote, "idx_premium_uuids_nickname", PREMIUM_UUIDS_TABLE, "NICKNAME"));
        createIndexIfMissing(connectionSource, PREMIUM_UUIDS_TABLE, "idx_premium_uuids_last_seen",
                buildCreateIndexSql(quote, "idx_premium_uuids_last_seen", PREMIUM_UUIDS_TABLE, "LAST_SEEN"));
        createIndexIfMissing(connectionSource, AUTH_TABLE, "idx_auth_premiumuuid",
                buildCreateIndexSql(quote, "idx_auth_premiumuuid", AUTH_TABLE, COLUMN_PREMIUM_UUID));
        createIndexIfMissing(connectionSource, AUDIT_LOG_TABLE, "idx_audit_player",
                buildCreateIndexSql(quote, "idx_audit_player", AUDIT_LOG_TABLE, "PLAYER_LOWERCASE"));
        createIndexIfMissing(connectionSource, AUDIT_LOG_TABLE, "idx_audit_timestamp",
                buildCreateIndexSql(quote, "idx_audit_timestamp", AUDIT_LOG_TABLE, "TIMESTAMP"));
        createIndexIfMissing(connectionSource, QQ_BINDING_TABLE, "uq_qq_binding_platform_uuid",
                buildCreateUniqueIndexSql(quote, "uq_qq_binding_platform_uuid", QQ_BINDING_TABLE,
                        "PLATFORM", "UUID"));
        createIndexIfMissing(connectionSource, QQ_LINK_CODE_TABLE, "idx_qq_link_code_expires",
                buildCreateIndexSql(quote, "idx_qq_link_code_expires", QQ_LINK_CODE_TABLE, "EXPIRES_AT"));
        createIndexIfMissing(connectionSource, REMEMBER_TOKEN_TABLE, "idx_qq_remember_uuid",
                buildCreateIndexSql(quote, "idx_qq_remember_uuid", REMEMBER_TOKEN_TABLE, "UUID"));
    }

    private String buildCreateIndexSql(String quote, String indexName, String tableName, String columnName) {
        return "CREATE INDEX " + indexName + " ON " + quoteIdentifier(quote, tableName)
                + " (" + quoteIdentifier(quote, columnName) + ")";
    }

    private String buildCreateUniqueIndexSql(String quote, String indexName, String tableName,
                                             String... columns) {
        StringBuilder sql = new StringBuilder("CREATE UNIQUE INDEX ")
                .append(indexName)
                .append(" ON ")
                .append(quoteIdentifier(quote, tableName))
                .append(" (");
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(quoteIdentifier(quote, columns[i]));
        }
        return sql.append(')').toString();
    }

    private String quoteIdentifier(String quote, String identifier) {
        return quote + identifier + quote;
    }

    private void createIndexIfMissing(ConnectionSource connectionSource, String tableName, String indexName, String sql) {
        if (connectionSource == null) {
            return;
        }
        DatabaseConnection connection = null;
        try {
            connection = connectionSource.getReadWriteConnection(null);
            java.sql.Connection underlyingConnection = connection.getUnderlyingConnection();
            if (indexExists(underlyingConnection, tableName, indexName)) {
                if (logger.isDebugEnabled()) {
                    logger.debug(DB_MARKER, "Index {} already exists on table {}", indexName, tableName);
                }
                return;
            }
            connection.executeStatement(sql, DatabaseConnection.DEFAULT_RESULT_FLAGS);
        } catch (SQLException e) {
            String normalizedMessage = normalizeSqlMessage(e);
            if (normalizedMessage.contains("already exists") || normalizedMessage.contains("duplicate")) {
                logger.debug(DB_MARKER, "Index already exists: {}", e.getMessage());
            } else {
                logger.error(DB_MARKER, "Index creation FAILED (not a duplicate): {}", sql, e);
            }
        } finally {
            if (connection != null) {
                try {
                    connectionSource.releaseConnection(connection);
                } catch (SQLException e) {
                    logger.error(DB_MARKER, "Failed to release connection after index creation", e);
                }
            }
        }
    }

    private boolean indexExists(java.sql.Connection connection, String tableName, String indexName) throws SQLException {
        java.sql.DatabaseMetaData metaData = connection.getMetaData();
        MetadataScope scope = metadataScope(connection);
        String resolvedTableName = findTableName(metaData, scope, tableName);
        return resolvedTableName != null
                && indexExists(metaData, scope, resolvedTableName, indexName);
    }

    private boolean indexExists(java.sql.DatabaseMetaData metaData, MetadataScope scope,
                                String tableName, String indexName) throws SQLException {
        try (java.sql.ResultSet indexes = metaData.getIndexInfo(
                scope.catalog(), scope.schema(), tableName, false, false)) {
            while (indexes.next()) {
                String existingIndex = indexes.getString("INDEX_NAME");
                if (existingIndex != null && existingIndex.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String identifierQuote(DatabaseType dbType) {
        return dbType == DatabaseType.POSTGRESQL ? "\"" : "`";
    }

    private String normalizeSqlMessage(SQLException e) {
        String message = e.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    private record ColumnMigrationResult(boolean hasPremiumUuid, boolean hasTotpToken, boolean hasIssuedTime,
                                         boolean hasConflictMode, boolean hasConflictTimestamp,
                                         boolean hasOriginalNickname, boolean hasPreserveUuid,
                                         boolean hasCredentialEpoch) {
    }

    record MigrationResult(int legacyUuidCandidates, int legacyUuidRowsMarked) {
    }

    private record MetadataScope(String catalog, String schema) {
    }
}
