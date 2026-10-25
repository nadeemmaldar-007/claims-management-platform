package com.apac.claims.claim.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.apac.claims.claim.api.dto.ClaimDetailResponse;
import com.apac.claims.claim.api.dto.SettleClaimRequest;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAction;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimWorkflow;
import com.apac.claims.claim.domain.PaymentMethod;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.shared.event.DomainEventPublisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pays an approved claim. The order of the steps is the whole point: who may act, the status, then the payment gateway,
 * and only after the gateway has answered the state change, the timeline entry and the event. If the gateway fails the
 * exception rolls the transaction back, so the claim stays APPROVED and nothing was written. If something fails after
 * the gateway has paid, the payment is not lost: the gateway keeps its record of its own, and a retry gets the same
 * reference instead of a second payment.
 */
@Service
public class ClaimSettlementService {

    private static final Logger log = LoggerFactory.getLogger(ClaimSettlementService.class);

    private final ClaimRepository claims;
    private final PaymentGateway gateway;
    private final ClaimActionSupport support;
    private final TimelineService timeline;
    private final DomainEventPublisher eventPublisher;
    private final ClaimEvents events;
    private final ClaimMapper mapper;
    private final Clock clock;

    public ClaimSettlementService(ClaimRepository claims, PaymentGateway gateway, ClaimActionSupport support,
                                  TimelineService timeline, DomainEventPublisher eventPublisher, ClaimEvents events,
                                  ClaimMapper mapper, Clock clock) {
        this.claims = claims;
        this.gateway = gateway;
        this.support = support;
        this.timeline = timeline;
        this.eventPublisher = eventPublisher;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The assigned officer or a manager settles an APPROVED claim; the amount paid is the approved amount. */
    @Transactional
    public ClaimDetailResponse settle(String claimNumber, SettleClaimRequest request, AppUser caller) {
        AppUser actor = support.reload(caller);
        support.requireStaff(actor);
        Claim claim = support.load(claimNumber, actor);
        support.requireAssigneeOrManager(claim, actor, "settle this claim");
        ClaimStatus from = claim.getStatus();
        ClaimStatus to = ClaimWorkflow.nextStatus(from, ClaimAction.SETTLE);

        PaymentReceipt receipt = gateway.pay(new PaymentRequest(claim.getClaimNumber(), claim.getApprovedAmount(),
                claim.getCurrencyCode(), request.paymentMethod()));

        claim.setStatus(to);
        claim.setPaymentMethod(request.paymentMethod());
        claim.setPaymentReference(receipt.reference());
        claim.setSettledAmount(claim.getApprovedAmount());
        claim.setSettledAt(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        claims.saveAndFlush(claim);

        String remarks = request.remarks() == null || request.remarks().isBlank() ? null : request.remarks().trim();
        timeline.record(claim, TimelineEntryType.SETTLED, from, to, actor.getUserId(),
                "Claim settled: " + claim.getSettledAmount().toPlainString() + " " + claim.getCurrencyCode()
                        + " paid by " + words(request.paymentMethod()) + ", payment reference " + receipt.reference()
                        + "." + (remarks == null ? "" : " " + remarks));
        eventPublisher.publish(events.settled(claim));
        log.info("Claim settled claimNumber={} reference={} by={}", claim.getClaimNumber(), receipt.reference(),
                actor.getUserId());
        return mapper.toDetail(claim, actor);
    }

    private static String words(PaymentMethod method) {
        return method == PaymentMethod.CHEQUE ? "cheque" : "bank transfer";
    }
}
