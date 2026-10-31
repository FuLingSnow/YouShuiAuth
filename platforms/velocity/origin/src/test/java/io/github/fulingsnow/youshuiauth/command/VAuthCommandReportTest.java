package io.github.fulingsnow.youshuiauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import io.github.fulingsnow.youshuiauth.YouShuiAuth;
import io.github.fulingsnow.youshuiauth.cache.AuthCache;
import io.github.fulingsnow.youshuiauth.config.Settings;
import io.github.fulingsnow.youshuiauth.database.DatabaseConfig;
import io.github.fulingsnow.youshuiauth.database.DatabaseManager;
import io.github.fulingsnow.youshuiauth.i18n.Messages;
import io.github.fulingsnow.youshuiauth.report.ReportService;
import io.github.fulingsnow.youshuiauth.util.VirtualThreadExecutorProvider;
import org.bstats.velocity.Metrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@code /vauth report} subcommand in {@link VAuthCommand}.
 * Verifies the disabled-gate, success path, failure path, and null-service guard.
 * Uses {@link InlineCommandContext} so the async task runs synchronously in-test.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("java:S100")
class VAuthCommandReportTest {

    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    @Mock
    private ProxyServer proxyServer;
    @Mock
    private org.slf4j.Logger logger;
    @Mock
    private CommandSource commandSource;
    @Mock
    private ReportService reportService;

    private YouShuiAuth plugin;
    private Messages messages;
    private Settings settings;
    private AuthCache authCache;
    private StubDatabaseManager databaseManager;
    private InlineCommandContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        when(logger.isDebugEnabled()).thenReturn(false);
        when(logger.isInfoEnabled()).thenReturn(false);
        when(logger.isWarnEnabled()).thenReturn(false);
        when(logger.isErrorEnabled()).thenReturn(false);

        Metrics.Factory metricsFactory = mock(Metrics.Factory.class);
        plugin = new YouShuiAuth(proxyServer, logger, Path.of(".test-report-cmd"), metricsFactory);

        messages = new Messages();
        messages.setLanguage("en");
        settings = new TestValidationSettings(Path.of(".test-report-cmd"), 6, 32);
        authCache = new AuthCache(
                new AuthCache.AuthCacheConfig(60, 10000, 1000, 10000, 5, 5, 1, 60),
                settings,
                messages
        );
        databaseManager = new StubDatabaseManager(
                DatabaseConfig.forLocalDatabase("H2", "report-cmd-test"), messages);
        ctx = new InlineCommandContext(new YouShuiAuthServicesView(plugin),
                databaseManager, authCache, settings, messages);

        when(commandSource.hasPermission("youshuiauth.admin")).thenReturn(true);
        setReportService(reportService);
    }

    @AfterEach
    void tearDown() throws Exception {
        setExecutorShutdown(false);
    }

    @Test
    void report_disabledByConfig_sendsDisabledMessage() {
        setReportEnabled(false);

        VAuthCommand command = new VAuthCommand(ctx);
        command.execute(invocation(commandSource, "report"));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(commandSource).sendMessage(captor.capture());
        List<String> sent = capturedTexts(captor);
        assertTrue(sent.contains(expectedText("admin.report.disabled")),
                () -> "Expected disabled message, got: " + sent);
        verify(reportService, never()).generateAndUpload(any(Settings.OperationSettings.class));
    }

    @Test
    void report_enabledAndUploadSucceeds_sendsSuccessWithUrl() throws InterruptedException {
        when(reportService.generateAndUpload(any(Settings.OperationSettings.class))).thenReturn(
                ReportService.ReportResult.success("https://mclo.gs/abc123"));

        VAuthCommand command = new VAuthCommand(ctx);
        command.execute(invocation(commandSource, "report"));

        List<String> sent = awaitSent(list -> list.contains(
                expectedText("admin.report.success", "https://mclo.gs/abc123")));
        assertTrue(sent.contains(expectedText("admin.report.generating")),
                () -> "Expected generating message, got: " + sent);
        assertTrue(sent.contains(expectedText("admin.report.warning")),
                () -> "Expected warning message, got: " + sent);
        assertTrue(sent.contains(expectedText("admin.report.success", "https://mclo.gs/abc123")),
                () -> "Expected success message with URL, got: " + sent);
    }

    @Test
    void report_enabledAndUploadFails_sendsFailureWithError() throws InterruptedException {
        when(reportService.generateAndUpload(any(Settings.OperationSettings.class))).thenReturn(
                ReportService.ReportResult.failure("HTTP 500: server error"));

        VAuthCommand command = new VAuthCommand(ctx);
        command.execute(invocation(commandSource, "report"));

        List<String> sent = awaitSent(list -> list.contains(
                expectedText("admin.report.failed", "HTTP 500: server error")));
        assertTrue(sent.contains(expectedText("admin.report.failed", "HTTP 500: server error")),
                () -> "Expected failure message with error, got: " + sent);
        assertFalse(sent.stream().anyMatch(s -> s.contains("mclo.gs/")),
                () -> "Should not contain a URL on failure: " + sent);
    }

    @Test
    void report_reportServiceNull_sendsFailureMessage() throws Exception {
        setReportService(null);

        VAuthCommand command = new VAuthCommand(ctx);
        command.execute(invocation(commandSource, "report"));

        List<String> sent = awaitSent(list -> list.stream()
                .anyMatch(s -> s.contains("Failed to generate report")));

        assertTrue(sent.stream().anyMatch(s -> s.contains("Failed to generate report")),
                () -> "Expected failure message when service is null, got: " + sent);
    }

    @Test
    void report_suggestInSubcommands() {
        VAuthCommand command = new VAuthCommand(ctx);

        SimpleCommand.Invocation inv = invocation(commandSource, "");
        List<String> suggestions = command.suggest(inv);

        assertTrue(suggestions.contains("report"),
                () -> "Expected 'report' in suggestions, got: " + suggestions);
    }

    private SimpleCommand.Invocation invocation(CommandSource source, String... args) {
        SimpleCommand.Invocation inv = mock(SimpleCommand.Invocation.class);
        when(inv.source()).thenReturn(source);
        when(inv.arguments()).thenReturn(args);
        return inv;
    }

    private List<String> awaitSent(java.util.function.Predicate<List<String>> done)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        List<String> sent = List.of();
        while (System.currentTimeMillis() < deadline) {
            try {
                ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
                verify(commandSource, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
                sent = capturedTexts(captor);
                if (done.test(sent)) {
                    return sent;
                }
            } catch (org.mockito.exceptions.verification.NeverWantedButInvoked
                    | org.mockito.exceptions.verification.TooFewActualInvocations ignored) {

            }
            Thread.sleep(20);
        }
        return sent;
    }

    private List<String> capturedTexts(ArgumentCaptor<Component> captor) {
        return captor.getAllValues().stream()
                .map(PLAIN_TEXT::serialize)
                .toList();
    }

    private String expectedText(String key, Object... args) {
        return PLAIN_TEXT.serialize(messages.component(key, NamedTextColor.WHITE, args));
    }

    private void setReportService(ReportService service) throws Exception {
        Field field = YouShuiAuth.class.getDeclaredField("reportService");
        field.setAccessible(true);
        field.set(plugin, service);
    }

    private void setReportEnabled(boolean enabled) {
        ((TestValidationSettings) settings).setReportEnabledForTesting(enabled);
    }

    private void setExecutorShutdown(boolean shutdown) throws Exception {
        Field shutdownField = VirtualThreadExecutorProvider.class.getDeclaredField("SHUTDOWN_INITIATED");
        shutdownField.setAccessible(true);
        AtomicBoolean shutdownFlag = (AtomicBoolean) shutdownField.get(null);
        shutdownFlag.set(shutdown);
    }

    private static final class InlineCommandContext extends VelocityCommandContext {
        private InlineCommandContext(io.github.fulingsnow.youshuiauth.platform.AuthServices services, DatabaseManager databaseManager,
                                     AuthCache authCache, Settings settings, Messages messages) {
            super(services, databaseManager, authCache, settings, messages);
        }

        @Override
        void runAsyncCommand(io.github.fulingsnow.youshuiauth.platform.AuthSender source,
                             Runnable task, String errorKey) {
            task.run();
        }

        @Override
        void runAsyncCommandWithTimeout(io.github.fulingsnow.youshuiauth.platform.AuthSender source,
                                        Runnable task, String errorKey, String timeoutKey) {
            task.run();
        }
    }

    private static final class StubDatabaseManager extends DatabaseManager {
        private StubDatabaseManager(DatabaseConfig config, Messages messages) {
            super(config, messages);
        }
    }
}
