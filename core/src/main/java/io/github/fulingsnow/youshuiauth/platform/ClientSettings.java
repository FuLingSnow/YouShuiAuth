package io.github.fulingsnow.youshuiauth.platform;

public record ClientSettings(
        String locale,
        String chatMode,
        boolean chatColors,
        String skinParts,
        String mainHand,
        boolean clientListingAllowed,
        boolean textFilteringEnabled,
        String particleStatus) {
}
