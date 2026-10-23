package io.github.fulingsnow.youshuiauth.util;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import io.github.fulingsnow.youshuiauth.platform.ClientSettings;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 新平台（无客户端设置 API 的服务端）使用的指纹口径 v2：与代理端的 v1 不同，
 * 哈希前缀为 {@code dev2|} 且末尾附加平台标识，两者的免密记录天然隔离。
 */
public final class PlatformFingerprints {

    public static final String HASH_PREFIX = "dev2|";

    private static final HexFormat HEX = HexFormat.of();

    private PlatformFingerprints() {
    }

    public static String fingerprint(AuthPlayer player, boolean includeSubnet, String platformTag) {
        return clientAttributes(player, includeSubnet, platformTag);
    }

    /**
     * 字段拼接的公共实现：brand|protocol|[settings...]|vhost[|platformTag][|subnet]。
     * extraTag 为 v2 的平台标识；v1（代理端存量口径）传 null 即不含该段。
     * 平台拿不到客户端设置时走 no-settings 分支，不参与哈希。
     */
    public static String clientAttributes(AuthPlayer player, boolean includeSubnet,
                                          String extraTag) {
        StringBuilder builder = new StringBuilder(128);
        builder.append(nullToUnknown(player.clientBrand()));
        builder.append('|').append(player.protocolVersion());

        Optional<ClientSettings> settings = player.settings();
        if (settings.isPresent()) {
            ClientSettings current = settings.get();
            builder.append('|').append(nullToUnknown(current.locale()));
            builder.append('|').append(current.chatMode());
            builder.append('|').append(current.chatColors());
            builder.append('|').append(current.skinParts());
            builder.append('|').append(current.mainHand());
            builder.append('|').append(current.clientListingAllowed());
            builder.append('|').append(current.textFilteringEnabled());
            builder.append('|').append(current.particleStatus());
        } else {
            builder.append("|no-settings");
        }

        builder.append('|').append(player.virtualHost()
                .map(address -> address.getHostString() + ":" + address.getPort())
                .orElse("no-vhost"));
        if (extraTag != null && !extraTag.isBlank()) {
            builder.append('|').append(nullToUnknown(extraTag));
        }

        if (includeSubnet) {
            builder.append('|').append(subnetPrefix(player.address() == null
                    ? null : player.address().getHostAddress()));
        }
        return builder.toString();
    }

    public static String hash(String uuid, String fingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(
                    (HASH_PREFIX + uuid + "|" + fingerprint).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JVM", e);
        }
    }

    /**
     * 代理端存量口径 v1：无平台标识段，前缀 {@code dev1|}。哈希入参的拼接仍由调用方
     * 通过 {@link #clientAttributes} 生成，保证与 v2 共享字段顺序。
     */
    public static String hashV1(String uuid, String fingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(
                    ("dev1|" + uuid + "|" + fingerprint).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JVM", e);
        }
    }

    public static String subnetLabel(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            byte[] raw = address.getAddress();
            if (address instanceof Inet4Address) {
                return (raw[0] & 0xFF) + "." + (raw[1] & 0xFF) + "." + (raw[2] & 0xFF) + ".x";
            }
            if (address instanceof Inet6Address) {
                StringBuilder builder = new StringBuilder(24);
                for (int i = 0; i < 8; i += 2) {
                    if (i > 0) {
                        builder.append(':');
                    }
                    builder.append(Integer.toHexString(((raw[i] & 0xFF) << 8) | (raw[i + 1] & 0xFF)));
                }
                return builder.append("::/64").toString();
            }
            return "unknown";
        } catch (IllegalArgumentException | java.net.UnknownHostException e) {
            return "unknown";
        }
    }

    public static boolean sameSubnet(String firstIp, String secondIp) {
        if (firstIp == null || secondIp == null || firstIp.isBlank() || secondIp.isBlank()) {
            return false;
        }
        try {
            InetAddress first = InetAddress.getByName(firstIp);
            InetAddress second = InetAddress.getByName(secondIp);
            if (first instanceof Inet4Address && second instanceof Inet4Address) {
                return prefixMatches(first.getAddress(), second.getAddress(), 3);
            }
            if (first instanceof Inet6Address && second instanceof Inet6Address) {
                return prefixMatches(first.getAddress(), second.getAddress(), 8);
            }
            return first.equals(second);
        } catch (IllegalArgumentException | java.net.UnknownHostException e) {
            return firstIp.equals(secondIp);
        }
    }

    private static boolean prefixMatches(byte[] first, byte[] second, int bytes) {
        if (first.length != second.length) {
            return false;
        }
        for (int i = 0; i < bytes; i++) {
            if (first[i] != second[i]) {
                return false;
            }
        }
        return true;
    }

    private static String subnetPrefix(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown-subnet";
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            int bytes = address instanceof Inet6Address ? 8 : 3;
            byte[] raw = address.getAddress();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < bytes; i++) {
                builder.append(raw[i]).append('.');
            }
            return builder.append(address instanceof Inet6Address ? "v6" : "v4").toString();
        } catch (IllegalArgumentException | java.net.UnknownHostException e) {
            return ip;
        }
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.toLowerCase(Locale.ROOT);
    }
}
