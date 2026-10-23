package io.github.fulingsnow.youshuiauth.runtime;

import org.slf4j.Logger;

/**
 * 实例标识：用于实例心跳与 {@code /ysauth instances} 展示，不参与认证判定。
 * 未配置时自动生成并写回配置文件，
 * 避免「空 id 让心跳锁失效」与「新装直接拒绝启动」两个极端。
 */
public final class DeploymentIdentity {

    public static final String CONFIG_KEY = "deployment.id";

    private DeploymentIdentity() {
    }

    /** 未配置时按「平台-主机名-短码」生成一个稳定且可读的实例标识。 */
    public static String generate(String platformId) {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            host = "host";
        }
        host = host.replaceAll("[^A-Za-z0-9-]", "-");
        if (host.length() > 24) {
            host = host.substring(0, 24);
        }
        String suffix = Integer.toHexString((int) (System.nanoTime() & 0xffff));
        return platformId + "-" + host + "-" + suffix;
    }

    public static boolean warnIfUnset(Logger logger, String deploymentId, String platformId) {
        String id = deploymentId == null ? "" : deploymentId.trim();
        if (id.isEmpty()) {
            logger.warn("未配置 {}（平台={}）：本实例无法在 /ysauth instances 中标识；"
                            + "留空启动会自动生成", CONFIG_KEY, platformId);
            return false;
        }
        logger.info("deployment.id={}（平台={}）", id, platformId);
        return true;
    }
}
