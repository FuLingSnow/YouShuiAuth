package net.rafalohaki.veloauth.qq;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqDeviceApprovalsTest {

    private static final String PLATFORM = "qq";
    private static final String QQ_ID = "10001";

    @Test
    void consume_isSingleUse() {
        QqDeviceApprovals approvals = new QqDeviceApprovals(120);
        UUID uuid = UUID.randomUUID();
        approvals.put(pending(uuid, QQ_ID, System.currentTimeMillis() + 60_000L));

        Optional<QqDeviceApprovals.PendingDeviceApproval> first = approvals.consume(PLATFORM, QQ_ID);
        assertTrue(first.isPresent());
        assertEquals(uuid, first.orElseThrow().uuid());
        assertTrue(approvals.consume(PLATFORM, QQ_ID).isEmpty());
    }

    @Test
    void consumeByApprovalId_isSingleUseAndCleansIndexes() {
        QqDeviceApprovals approvals = new QqDeviceApprovals(120);
        UUID uuid = UUID.randomUUID();
        QqDeviceApprovals.PendingDeviceApproval approval =
                pending(uuid, QQ_ID, System.currentTimeMillis() + 60_000L);
        approvals.put(approval);

        assertTrue(approvals.peekByApprovalId(approval.approvalId()).isPresent());
        assertEquals(approval, approvals.consumeByApprovalId(approval.approvalId())
                .orElseThrow());
        assertTrue(approvals.consumeByApprovalId(approval.approvalId()).isEmpty());
        assertTrue(approvals.peekByApprovalId(approval.approvalId()).isEmpty());
        assertTrue(approvals.peek(PLATFORM, QQ_ID).isEmpty());
        assertTrue(approvals.consumeByUuid(uuid).isEmpty());
    }

    @Test
    void secondRequestForSameQq_replacesPreviousEntry() {
        QqDeviceApprovals approvals = new QqDeviceApprovals(120);
        UUID firstUuid = UUID.randomUUID();
        UUID secondUuid = UUID.randomUUID();

        QqDeviceApprovals.PendingDeviceApproval first =
                pending(firstUuid, QQ_ID, System.currentTimeMillis() + 60_000L);
        approvals.put(first);
        approvals.put(pending(secondUuid, QQ_ID, System.currentTimeMillis() + 60_000L));

        assertEquals(secondUuid, approvals.consume(PLATFORM, QQ_ID).orElseThrow().uuid());
        // The replaced account must not be able to consume the newer entry either.
        assertTrue(approvals.consumeByUuid(firstUuid).isEmpty());
        // ...and the replaced approvalId must be dead as well.
        assertTrue(approvals.consumeByApprovalId(first.approvalId()).isEmpty());
    }

    @Test
    void consumeByUuid_removesTheMatchingEntry() {
        QqDeviceApprovals approvals = new QqDeviceApprovals(120);
        UUID uuid = UUID.randomUUID();
        approvals.put(pending(uuid, QQ_ID, System.currentTimeMillis() + 60_000L));

        assertTrue(approvals.consumeByUuid(uuid).isPresent());
        assertTrue(approvals.consume(PLATFORM, QQ_ID).isEmpty());
    }

    @Test
    void entryExpiresAfterTtl() throws InterruptedException {
        QqDeviceApprovals approvals = new QqDeviceApprovals(1);
        UUID uuid = UUID.randomUUID();
        approvals.put(pending(uuid, QQ_ID, System.currentTimeMillis() + 60_000L));

        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline && approvals.peek(PLATFORM, QQ_ID).isPresent()) {
            Thread.sleep(50L);
        }
        assertTrue(approvals.peek(PLATFORM, QQ_ID).isEmpty());
        assertTrue(approvals.consume(PLATFORM, QQ_ID).isEmpty());
        assertTrue(approvals.consumeByUuid(uuid).isEmpty());
    }

    @Test
    void blankIdentifiers_areRejected() {
        QqDeviceApprovals approvals = new QqDeviceApprovals(120);

        assertTrue(approvals.consume(null, QQ_ID).isEmpty());
        assertTrue(approvals.consume(PLATFORM, "  ").isEmpty());
        assertTrue(approvals.peek("", QQ_ID).isEmpty());
        assertTrue(approvals.consumeByUuid(null).isEmpty());
        assertTrue(approvals.peekByApprovalId(null).isEmpty());
        assertTrue(approvals.consumeByApprovalId("").isEmpty());
        assertFalse(approvals.peek(PLATFORM, QQ_ID).isPresent());
    }

    @Test
    void expiredEntry_isReportedByTimestamp() {
        QqDeviceApprovals.PendingDeviceApproval approval =
                pending(UUID.randomUUID(), QQ_ID, System.currentTimeMillis() - 1L);

        assertTrue(approval.isExpired(System.currentTimeMillis()));
    }

    private static QqDeviceApprovals.PendingDeviceApproval pending(UUID uuid, String qqId, long expiresAt) {
        return new QqDeviceApprovals.PendingDeviceApproval(
                PLATFORM, qqId, UUID.randomUUID().toString().replace("-", ""), uuid,
                null, "fingerprint", expiresAt);
    }
}
