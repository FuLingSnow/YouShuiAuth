package io.github.fulingsnow.youshuiauth.util;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.SkinParts;
import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.velocity.VelocityAuthPlayer;

/**
 * 指纹工具薄壳：字段拼接、子网掩码与哈希都在 core 的 {@link PlatformFingerprints}
 * （v1 与 v2 共享字段顺序与算法）。这里只保留 Velocity 专属的
 * {@code PlayerSettings → ClientSettings} 适配与 v1 前缀约定。
 */
public final class DeviceFingerprints {

    private DeviceFingerprints() {
    }

    public static String fingerprint(Player player, boolean includeSubnet) {
        return PlatformFingerprints.clientAttributes(
                VelocityAuthPlayer.of(player), includeSubnet, null);
    }

    public static String fingerprint(AuthPlayer player, boolean includeSubnet) {
        return PlatformFingerprints.clientAttributes(player, includeSubnet, null);
    }

    public static String hash(String uuid, String fingerprint) {
        return PlatformFingerprints.hashV1(uuid, fingerprint);
    }

    public static boolean sameSubnet(String firstIp, String secondIp) {
        return PlatformFingerprints.sameSubnet(firstIp, secondIp);
    }

    public static String subnetLabel(String ip) {
        return PlatformFingerprints.subnetLabel(ip);
    }

    public static String skinPartsKey(SkinParts parts) {
        return io.github.fulingsnow.youshuiauth.platform.fingerprint.SkinPartsCodec.encode(
                parts.hasCape(), parts.hasJacket(),
                parts.hasLeftSleeve(), parts.hasRightSleeve(),
                parts.hasLeftPants(), parts.hasRightPants(), parts.hasHat());
    }
}
