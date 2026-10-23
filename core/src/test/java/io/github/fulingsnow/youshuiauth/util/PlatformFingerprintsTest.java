package io.github.fulingsnow.youshuiauth.util;

import io.github.fulingsnow.youshuiauth.platform.AuthPlayer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformFingerprintsTest {

    private static AuthPlayer player(String brand, int protocol, String ip)
            throws Exception {
        AuthPlayer player = mock(AuthPlayer.class);
        when(player.uniqueId()).thenReturn(UUID.fromString("00000000-0000-0000-0000-0000000000aa"));
        when(player.clientBrand()).thenReturn(brand);
        when(player.protocolVersion()).thenReturn(protocol);
        when(player.address()).thenReturn(InetAddress.getByName(ip));
        when(player.virtualHost()).thenReturn(Optional.empty());
        when(player.settings()).thenReturn(Optional.empty());
        return player;
    }

    @Test
    void noSettingsServersProduceStableFingerprint() throws Exception {
        AuthPlayer first = player("vanilla", 776, "203.0.113.10");
        AuthPlayer second = player("vanilla", 776, "203.0.113.11");
        String base = PlatformFingerprints.fingerprint(first, false, "paper");
        String other = PlatformFingerprints.fingerprint(second, false, "paper");

        assertEquals(base, other, "同一品牌/协议/平台且不含子网时应稳定");
        assertTrue(base.contains("|no-settings"), "无客户端设置时必须显式标记");
        assertTrue(base.contains("|paper"), "指纹必须包含平台标识");
    }

    @Test
    void platformTagSeparatesDeployments() throws Exception {
        AuthPlayer player = player("vanilla", 776, "203.0.113.10");
        String paper = PlatformFingerprints.fingerprint(player, false, "paper");
        String fabric = PlatformFingerprints.fingerprint(player, false, "fabric");
        assertNotEquals(paper, fabric);
    }

    @Test
    void subnetInclusionChangesFingerprint() throws Exception {
        AuthPlayer near = player("vanilla", 776, "203.0.113.10");
        AuthPlayer far = player("vanilla", 776, "198.51.100.7");
        assertEquals(PlatformFingerprints.fingerprint(near, true, "paper"),
                PlatformFingerprints.fingerprint(
                        player("vanilla", 776, "203.0.113.99"), true, "paper"));
        assertNotEquals(PlatformFingerprints.fingerprint(near, true, "paper"),
                PlatformFingerprints.fingerprint(far, true, "paper"));
    }

    @Test
    void hashUsesDev2PrefixSoItCannotCollideWithProxyRecords() {
        String uuid = "00000000-0000-0000-0000-0000000000aa";
        String v2 = PlatformFingerprints.hash(uuid, "vanilla|776|no-settings|no-vhost|paper");
        String legacy = DeviceFingerprintsV1Hash.hash(uuid, "vanilla|776|no-settings|no-vhost");
        assertNotEquals(v2, legacy, "v2 与代理端 v1 的哈希必须不同");
        assertEquals(64, v2.length());
    }

    @Test
    void subnetLabelAndMatchFollowPolicy() {
        assertEquals("203.0.113.x", PlatformFingerprints.subnetLabel("203.0.113.10"));
        assertTrue(PlatformFingerprints.sameSubnet("203.0.113.10", "203.0.113.200"));
        assertFalse(PlatformFingerprints.sameSubnet("203.0.113.10", "198.51.100.10"));
        assertEquals("unknown", PlatformFingerprints.subnetLabel(""));
    }

    /** 复刻代理端 v1 的哈希口径，仅用于断言两代前缀不同。 */
    private static final class DeviceFingerprintsV1Hash {
        static String hash(String uuid, String fingerprint) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                return java.util.HexFormat.of().formatHex(digest.digest(
                        ("dev1|" + uuid + "|" + fingerprint).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
