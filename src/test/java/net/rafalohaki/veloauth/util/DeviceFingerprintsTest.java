package net.rafalohaki.veloauth.util;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.player.SkinParts;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceFingerprintsTest {

    @Test
    void sameSubnetIpv4ComparesFirstThreeOctets() {
        assertTrue(DeviceFingerprints.sameSubnet("192.168.1.5", "192.168.1.99"));
        assertFalse(DeviceFingerprints.sameSubnet("192.168.1.5", "192.168.2.5"));
    }

    @Test
    void sameSubnetIpv6ComparesFirstSixtyFourBits() {
        assertTrue(DeviceFingerprints.sameSubnet("2001:db8::1", "2001:db8::ff"));
        assertFalse(DeviceFingerprints.sameSubnet("2001:db8::1", "2001:db9::1"));
    }

    @Test
    void sameSubnetRejectsBlankInput() {
        assertFalse(DeviceFingerprints.sameSubnet(null, "192.168.1.5"));
        assertFalse(DeviceFingerprints.sameSubnet("192.168.1.5", " "));
    }

    @Test
    void hashBindsFingerprintToTheAccount() {
        String first = DeviceFingerprints.hash(
                "4013c59b-55aa-3bbe-8169-f6623b88e2d3", "vanilla|26.3|zh_cn");
        assertEquals(first, DeviceFingerprints.hash(
                "4013c59b-55aa-3bbe-8169-f6623b88e2d3", "vanilla|26.3|zh_cn"));
        assertNotEquals(first, DeviceFingerprints.hash(
                "11111111-2222-3333-4444-555555555555", "vanilla|26.3|zh_cn"));
        assertNotEquals(first, DeviceFingerprints.hash(
                "4013c59b-55aa-3bbe-8169-f6623b88e2d3", "fabric|26.3|zh_cn"));
    }

    @Test
    void subnetFlagChangesTheFingerprint() {
        Player player = probePlayer();
        String withSubnet = DeviceFingerprints.fingerprint(player, true);
        String withoutSubnet = DeviceFingerprints.fingerprint(player, false);
        assertNotEquals(withSubnet, withoutSubnet);
        assertTrue(withSubnet.endsWith("v4"));
    }

    @Test
    void fingerprintIsStableForTheSameProbe() {
        Player player = probePlayer();
        assertEquals(DeviceFingerprints.fingerprint(player, true),
                DeviceFingerprints.fingerprint(player, true));
    }

    private Player probePlayer() {
        Player player = mock(Player.class);
        when(player.getClientBrand()).thenReturn("vanilla");
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_5);
        when(player.hasSentPlayerSettings()).thenReturn(true);

        PlayerSettings settings = mock(PlayerSettings.class);
        when(settings.getLocale()).thenReturn(Locale.SIMPLIFIED_CHINESE);
        when(settings.getChatMode()).thenReturn(PlayerSettings.ChatMode.SHOWN);
        when(settings.hasChatColors()).thenReturn(true);
        when(settings.getSkinParts()).thenReturn(new SkinParts((byte) 127));
        when(settings.getMainHand()).thenReturn(PlayerSettings.MainHand.RIGHT);
        when(settings.isClientListingAllowed()).thenReturn(true);
        when(settings.isTextFilteringEnabled()).thenReturn(false);
        when(settings.getParticleStatus()).thenReturn(PlayerSettings.ParticleStatus.ALL);
        when(player.getPlayerSettings()).thenReturn(settings);

        when(player.getVirtualHost()).thenReturn(
                Optional.of(new InetSocketAddress("play.example.com", 25565)));
        when(player.getRemoteAddress()).thenReturn(
                new InetSocketAddress("192.168.1.5", 12345));
        return player;
    }
}
