package io.github.fulingsnow.youshuiauth.platform;

import io.github.fulingsnow.youshuiauth.config.Settings;

@FunctionalInterface
public interface ReportOps {

    Outcome generateAndUpload(Settings.OperationSettings settings);

    record Outcome(boolean success, String url, String error) {
    }
}
