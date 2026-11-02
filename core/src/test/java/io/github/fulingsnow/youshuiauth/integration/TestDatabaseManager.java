package io.github.fulingsnow.youshuiauth.integration;

import io.github.fulingsnow.youshuiauth.database.DatabaseConfig;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.model.RegisteredPlayer;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test stub for DatabaseManager to avoid Mockito inline mocking issues on Java 21.
 * Provides controllable results for initialize() and findPlayerByNickname().
 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
class TestDatabaseManager extends DatabaseManager {

    private final Map<String, CompletableFuture<DatabaseManager.DbResult<RegisteredPlayer>>> findResults = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<DatabaseManager.DbResult<Boolean>>> premiumResults = new ConcurrentHashMap<>();
    private CompletableFuture<Boolean> initResult = CompletableFuture.completedFuture(true);
    private CompletableFuture<DatabaseManager.DbResult<RegisteredPlayer>> nicknameOrPremiumUuidResult;

    TestDatabaseManager(DatabaseConfig config, Messages messages) {
        super(config, messages);
    }

    @Override
    public CompletableFuture<Boolean> initialize() {
        return initResult;
    }

    void setInitResult(CompletableFuture<Boolean> initResult) {
        this.initResult = initResult != null ? initResult : CompletableFuture.completedFuture(true);
    }

    void setFindResult(String nickname, CompletableFuture<DatabaseManager.DbResult<RegisteredPlayer>> result) {
        if (nickname != null && result != null) {
            findResults.put(nickname.toLowerCase(Locale.ROOT), result);
        }
    }

    void setPremiumResult(String username, CompletableFuture<DatabaseManager.DbResult<Boolean>> result) {
        if (username != null && result != null) {
            premiumResults.put(username, result);
        }
    }

    @Override
    public CompletableFuture<DatabaseManager.DbResult<RegisteredPlayer>> findPlayerByNickname(String nickname) {
        if (nickname == null || nickname.isEmpty()) {
            return CompletableFuture.completedFuture(DatabaseManager.DbResult.success(null));
        }
        return findResults.getOrDefault(nickname.toLowerCase(Locale.ROOT), CompletableFuture.completedFuture(DatabaseManager.DbResult.success(null)));
    }

    @Override
    public CompletableFuture<DatabaseManager.DbResult<Boolean>> isPremium(String username) {
        if (username == null || username.isEmpty()) {
            return CompletableFuture.completedFuture(DatabaseManager.DbResult.success(false));
        }
        return premiumResults.getOrDefault(username, CompletableFuture.completedFuture(DatabaseManager.DbResult.success(false)));
    }

    @Override
    public CompletableFuture<DatabaseManager.DbResult<RegisteredPlayer>> findPlayerWithRuntimeDetection(String username) {

        return findPlayerByNickname(username);
    }

    @Override
    public CompletableFuture<DatabaseManager.DbResult<Boolean>> savePlayer(RegisteredPlayer player) {

        return CompletableFuture.completedFuture(DatabaseManager.DbResult.success(true));
    }

    void setNicknameOrPremiumUuidResult(CompletableFuture<DatabaseManager.DbResult<RegisteredPlayer>> result) {
        this.nicknameOrPremiumUuidResult = result;
    }

    @Override
    public CompletableFuture<DatabaseManager.DbResult<RegisteredPlayer>> findPlayerByNicknameOrPremiumUuidReadOnly(
            String nickname, UUID premiumUuid) {
        if (nicknameOrPremiumUuidResult != null) {
            return nicknameOrPremiumUuidResult;
        }
        return super.findPlayerByNicknameOrPremiumUuidReadOnly(nickname, premiumUuid);
    }
}
