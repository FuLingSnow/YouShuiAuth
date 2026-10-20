package net.rafalohaki.veloauth.util;

import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.PlayerSettings;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Builds and hashes the device fingerprint used by auto-login: a SHA-256 over stable
 * client-reported attributes plus an optional subnet prefix. This is a convenience factor,
 * not a strong credential.
 */
public final class DeviceFingerprints {

    private static final HexFormat HEX = HexFormat.of();

    private DeviceFingerprints() {
    }

    /**
     * Builds the fingerprint string. {@code includeSubnet} must match the policy used when the
     * fingerprint was originally stored, otherwise stored and recomputed hashes diverge.
     */
    public static String fingerprint(Player player, boolean includeSubnet) {
        StringBuilder builder = new StringBuilder(128);
        builder.append(nullToUnknown(player.getClientBrand()));
        builder.append('|').append(player.getProtocolVersion().getProtocol());

        PlayerSettings settings = player.getPlayerSettings();
        if (player.hasSentPlayerSettings() && settings != null) {
            builder.append('|').append(nullToUnknown(settings.getLocale().toLanguageTag()));
            builder.append('|').append(settings.getChatMode());
            builder.append('|').append(settings.hasChatColors());
            builder.append('|').append(skinPartsKey(settings.getSkinParts()));
            builder.append('|').append(settings.getMainHand());
            builder.append('|').append(settings.isClientListingAllowed());
            builder.append('|').append(settings.isTextFilteringEnabled());
            builder.append('|').append(settings.getParticleStatus());
        } else {
            builder.append("|no-settings");
        }

        builder.append('|').append(virtualHost(player));

        if (includeSubnet) {
            builder.append('|').append(subnetPrefix(PlayerAddressUtils.getPlayerIp(player)));
        }
        return builder.toString();
    }

    /**
     * Binds a fingerprint to a single account: {@code SHA-256("dev1|" + uuid + "|" + fp)}.
     */
    public static String hash(String uuid, String fingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(
                    ("dev1|" + uuid + "|" + fingerprint).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JVM", e);
        }
    }

    /**
     * IPv4: same /24. IPv6: same /64. Anything unparseable compares exact-equal only.
     */
    public static boolean sameSubnet(String firstIp, String secondIp) {
        if (firstIp == null || secondIp == null || firstIp.isBlank() || secondIp.isBlank()) {
            return false;
        }
        try {
            // getByName on an IP literal performs no DNS lookup.
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

    private static String virtualHost(Player player) {
        InboundConnection connection = player;
        return connection.getVirtualHost()
                .map(address -> address.getHostString() + ":" + address.getPort())
                .orElse("no-vhost");
    }

    private static String skinPartsKey(com.velocitypowered.api.proxy.player.SkinParts parts) {
        StringBuilder builder = new StringBuilder(7);
        builder.append(parts.hasCape() ? 'c' : '-');
        builder.append(parts.hasJacket() ? 'j' : '-');
        builder.append(parts.hasLeftSleeve() ? 's' : '-');
        builder.append(parts.hasRightSleeve() ? 's' : '-');
        builder.append(parts.hasLeftPants() ? 'p' : '-');
        builder.append(parts.hasRightPants() ? 'p' : '-');
        builder.append(parts.hasHat() ? 'h' : '-');
        return builder.toString();
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.toLowerCase(Locale.ROOT);
    }
}
