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
        preflightLegacyMigration(connectionSource);
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
     * 迁移前的只读预检：只做检查，不执行任何 DDL。
     *
     * <p>发现问题时一次性汇总并抛出，使初始化走与 DDL 失败完全相同的优雅关闭链路，
     * 同时保证库不会停在半迁移状态。检查分三类：待迁移旧表的 owner、已存在目标表的
     * 读写权限、schema 的 CREATE 权限。</p>
     */
    private void preflightLegacyMigration(ConnectionSource connectionSource) throws SQLException {
        DatabaseType type = DatabaseType.fromName(config.getStorageType());
        if (type != DatabaseType.POSTGRESQL && type != DatabaseType.MYSQL) {
            return;
        }

        DatabaseConnection pooledConnection = connectionSource.getReadWriteConnection(null);
        try {
            java.sql.Connection connection = pooledConnection.getUnderlyingConnection();
            java.util.Map<String, String> existingTables = collectExistingTables(connection);
            java.util.List<String[]> pending = pendingLegacyRenames(existingTables);

            String quote = identifierQuote(type);
            String schema = type == DatabaseType.POSTGRESQL
                    ? currentSchemaName(connection)
                    : safeCatalog(connection);
            String currentUser = currentDatabaseUser(connection);

            java.util.List<String> problems = new java.util.ArrayList<>();
            java.util.List<String> fixes = new java.util.ArrayList<>();

            // 没有待迁移旧表时跳过 owner 检查，但已有表的读写权限与 schema 权限仍需检查
            if (!pending.isEmpty() && type == DatabaseType.POSTGRESQL && !isPostgresSuperuser(connection)) {
                collectOwnershipProblems(connection, pending, quote, schema, currentUser, problems, fixes);
            }
            collectTablePrivilegeProblems(connection, type, existingTables, quote, schema,
                    currentUser, problems, fixes);
            collectSchemaPrivilegeProblem(connection, type, schema, quote, currentUser, problems, fixes);

            if (problems.isEmpty()) {
                return;
            }
            throw new SQLException(buildPreflightReport(problems, fixes, currentUser));
        } finally {
            connectionSource.releaseConnection(pooledConnection);
        }
    }

    /** 收集「旧表存在且新表不存在」的待迁移项。 */
    private static java.util.List<String[]> pendingLegacyRenames(
            java.util.Map<String, String> existingTables) {
        java.util.List<String[]> pending = new java.util.ArrayList<>();
        for (String[] rename : LEGACY_TABLE_RENAMES) {
            String existingOld = existingTables.get(rename[0].toUpperCase(java.util.Locale.ROOT));
            if (existingOld != null && !existingTables.containsKey(rename[1])) {
                pending.add(new String[]{existingOld, rename[1]});
            }
        }
        return pending;
    }

    /** 待迁移旧表的 owner 必须等于当前账号，否则改名会被拒绝。 */
    private static void collectOwnershipProblems(java.sql.Connection connection,
                                                 java.util.List<String[]> pending, String quote,
                                                 String schema, String currentUser,
                                                 java.util.List<String> problems,
                                                 java.util.List<String> fixes) {
        for (String[] item : pending) {
            String owner = postgresTableOwner(connection, item[0]);
            if (owner == null || owner.equalsIgnoreCase(currentUser)) {
                continue;
            }
            problems.add(String.format("  · 表 %s 的 owner 是 %s，不是当前账号 %s",
                    item[0], owner, currentUser));
            fixes.add(String.format("ALTER TABLE %s.%s OWNER TO %s;",
                    quoteIdentifierStatic(quote, schema), quoteIdentifierStatic(quote, item[0]),
                    quoteIdentifierStatic(quote, currentUser)));
        }
    }

    /** 已存在的 YOUSHUI_* 表必须对当前账号可读写，否则迁移后立刻报 permission denied。 */
    private static void collectTablePrivilegeProblems(java.sql.Connection connection, DatabaseType type,
                                                      java.util.Map<String, String> existingTables,
                                                      String quote, String schema, String currentUser,
                                                      java.util.List<String> problems,
                                                      java.util.List<String> fixes) {
        java.util.LinkedHashSet<String> knownTables = new java.util.LinkedHashSet<>();
        for (String[] rename : LEGACY_TABLE_RENAMES) {
            knownTables.add(rename[1]);
        }
        for (String targetTable : knownTables) {
            String actual = existingTables.get(targetTable.toUpperCase(java.util.Locale.ROOT));
            if (actual == null || hasTableWritePrivilege(connection, type, schema, actual, quote)) {
                continue;
            }
            problems.add(String.format("  · 表 %s 对当前账号 %s 不可读写（owner 可能是其他账号且未授权）",
                    actual, currentUser));
            fixes.add(String.format("GRANT ALL ON TABLE %s.%s TO %s;",
                    quoteIdentifierStatic(quote, schema), quoteIdentifierStatic(quote, actual),
                    quoteIdentifierStatic(quote, currentUser)));
        }
    }

    /** schema 缺少 CREATE 权限时，迁移完成后的建表会失败。 */
    private static void collectSchemaPrivilegeProblem(java.sql.Connection connection, DatabaseType type,
                                                      String schema, String quote, String currentUser,
                                                      java.util.List<String> problems,
                                                      java.util.List<String> fixes) {
        if (hasSchemaCreatePrivilege(connection, type, schema)) {
            return;
        }
        problems.add(String.format("  · schema %s 缺少 CREATE 权限（迁移完成后建表需要）", schema));
        fixes.add(type == DatabaseType.POSTGRESQL
                ? String.format("GRANT CREATE ON SCHEMA %s TO %s;",
                        quoteIdentifierStatic(quote, schema), quoteIdentifierStatic(quote, currentUser))
                : String.format("GRANT CREATE ON `%s`.* TO '%s';", schema, currentUser));
    }

    /** 把问题与修复语句拼成一条可直接阅读的报错。 */
    private static String buildPreflightReport(java.util.List<String> problems,
                                               java.util.List<String> fixes, String currentUser) {
        StringBuilder report = new StringBuilder(512);
        report.append("迁移前置检查未通过，已在执行任何改名之前中止，数据库保持原样：")
                .append(System.lineSeparator());
        problems.forEach(line -> report.append(line).append(System.lineSeparator()));
        report.append("请用具备权限的账号执行以下语句（当前连接账号：")
                .append(currentUser).append("）：").append(System.lineSeparator());
        fixes.forEach(line -> report.append("  ").append(line).append(System.lineSeparator()));
        report.append("执行完成后重启服务端，迁移会自动继续。");
        return report.toString();
    }

    /** quoteIdentifier 的静态版本，供静态检查方法复用。 */
    private static String quoteIdentifierStatic(String quote, String identifier) {
        return quote + identifier + quote;
    }

    /** PostgreSQL 超级用户可改任意表，owner 检查对其无意义。 */
    private static boolean isPostgresSuperuser(java.sql.Connection connection) {
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows =
                     statement.executeQuery("SELECT current_setting('is_superuser')")) {
            return rows.next() && "on".equalsIgnoreCase(rows.getString(1));
        } catch (SQLException ignored) {
            return false;
        }
    }

    /** 读取当前库里的全部表名（大写键 → 原始表名），供迁移与预检共用。 */
    private java.util.Map<String, String> collectExistingTables(java.sql.Connection connection)
            throws SQLException {
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
        return existingTables;
    }

    private static String safeCatalog(java.sql.Connection connection) {
        try {
            String catalog = connection.getCatalog();
            return catalog == null ? "" : catalog;
        } catch (SQLException ignored) {
            return "";
        }
    }

    private static String currentSchemaName(java.sql.Connection connection) {
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("SELECT current_schema()")) {
            return rows.next() && rows.getString(1) != null ? rows.getString(1) : "public";
        } catch (SQLException ignored) {
            return "public";
        }
    }

    private static String currentDatabaseUser(java.sql.Connection connection) {
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("SELECT current_user")) {
            return rows.next() && rows.getString(1) != null ? rows.getString(1) : "";
        } catch (SQLException ignored) {
            return "";
        }
    }

    /** PostgreSQL：查表的 owner 角色名；查不到时返回 null（视为无法判定，不阻断）。 */
    private static String postgresTableOwner(java.sql.Connection connection, String tableName) {
        String sql = "SELECT pg_get_userbyid(c.relowner) FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = current_schema() AND c.relname = ?";
        try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException ignored) {
            return null;
        }
    }

    /** 检查当前账号对某张表是否具备读写权限；查询失败时不阻断，交给实际执行暴露。 */
    private static boolean hasTableWritePrivilege(java.sql.Connection connection, DatabaseType type,
                                                  String schema, String table, String quote) {
        try {
            if (type == DatabaseType.POSTGRESQL) {
                // PostgreSQL 会把未加引号的标识符折叠成小写，必须带引号传入完整限定名
                String qualified = quoteIdentifierStatic(quote, schema)
                        + "." + quoteIdentifierStatic(quote, table);
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT has_table_privilege(current_user, ?, 'SELECT,INSERT,UPDATE,DELETE')")) {
                    statement.setString(1, qualified);
                    try (java.sql.ResultSet rows = statement.executeQuery()) {
                        return !rows.next() || rows.getBoolean(1);
                    }
                }
            }
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES "
                            + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                            + "AND PRIVILEGE_TYPE IN ('SELECT','INSERT','UPDATE','DELETE')")) {
                statement.setString(1, schema);
                statement.setString(2, table);
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    return !rows.next() || rows.getInt(1) > 0;
                }
            }
        } catch (SQLException ignored) {
            return true;
        }
    }

    /** 检查当前账号在目标 schema 上是否具备 CREATE 权限；查询失败时不阻断，交给实际执行暴露。 */
    private static boolean hasSchemaCreatePrivilege(java.sql.Connection connection, DatabaseType type,
                                                    String schema) {
        try {
            if (type == DatabaseType.POSTGRESQL) {
                try (java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT has_schema_privilege(current_user, ?, 'CREATE')")) {
                    statement.setString(1, schema);
                    try (java.sql.ResultSet rows = statement.executeQuery()) {
                        return !rows.next() || rows.getBoolean(1);
                    }
                }
            }
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES "
                            + "WHERE TABLE_SCHEMA = ? AND PRIVILEGE_TYPE IN ('CREATE','ALL PRIVILEGES')")) {
                statement.setString(1, schema);
                try (java.sql.ResultSet rows = statement.executeQuery()) {
                    return !rows.next() || rows.getInt(1) > 0;
                }
            }
        } catch (SQLException ignored) {
            return true;
        }
    }

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
            java.util.Map<String, String> existingTables = collectExistingTables(connection);
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
