package io.github.fulingsnow.youshuiauth.integration;

import io.github.fulingsnow.youshuiauth.config.Settings;

import java.nio.file.Path;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
class TestSettings extends Settings {
    private final boolean debug;
    private final String authServer;

    TestSettings(Path dataDirectory, boolean debug, String authServer) {
        super(dataDirectory);
        this.debug = debug;
        this.authServer = authServer;
    }

    @Override
    public boolean isDebugEnabled() {
        return debug;
    }

    @Override
    public String getAuthServerName() {
        return authServer;
    }
}
