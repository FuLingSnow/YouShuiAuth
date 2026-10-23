package io.github.fulingsnow.youshuiauth.platform.fingerprint;

/**
 * 皮肤部件编码：把 7 个可见性开关压成定长 7 字符串，作为设备指纹的一部分。
 *
 * <p>口径说明（改动会让所有存量免密记录失配一次，务必谨慎）：
 * 位序固定为 披风/上衣/左袖/右袖/左裤/右裤/帽子，左右由位置区分，
 * 字符集复用 {@code s}/{@code p} 只是沿用上游 VeloAuth 的历史写法。</p>
 */
public final class SkinPartsCodec {

    private SkinPartsCodec() {
    }

    public static String encode(boolean cape, boolean jacket,
                                boolean leftSleeve, boolean rightSleeve,
                                boolean leftPants, boolean rightPants,
                                boolean hat) {
        StringBuilder builder = new StringBuilder(7);
        builder.append(cape ? 'c' : '-');
        builder.append(jacket ? 'j' : '-');
        builder.append(leftSleeve ? 's' : '-');
        builder.append(rightSleeve ? 's' : '-');
        builder.append(leftPants ? 'p' : '-');
        builder.append(rightPants ? 'p' : '-');
        builder.append(hat ? 'h' : '-');
        return builder.toString();
    }
}
