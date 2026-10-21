package net.rafalohaki.veloauth.qq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QqNotifyTest {

    @Test
    void twoArgumentConstructorDefaultsToAutoLogin() {
        QqNotify notify = new QqNotify("10001", "message");

        assertEquals(QqNotify.TYPE_AUTO_LOGIN, notify.type());
    }

    @Test
    void explicitDeviceApprovalTypeIsKept() {
        QqNotify notify = new QqNotify("10001", "message", QqNotify.TYPE_DEVICE_APPROVAL);

        assertEquals(QqNotify.TYPE_DEVICE_APPROVAL, notify.type());
    }

    @Test
    void blankTypeFallsBackToAutoLogin() {
        assertEquals(QqNotify.TYPE_AUTO_LOGIN, new QqNotify("10001", "m", " ").type());
        assertEquals(QqNotify.TYPE_AUTO_LOGIN, new QqNotify("10001", "m", null).type());
    }
}
