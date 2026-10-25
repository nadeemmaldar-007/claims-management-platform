package com.apac.claims.notification.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.notification.domain.NotificationType;
import com.apac.claims.shared.event.DomainEvent;
import com.apac.claims.shared.event.DomainEventHandler;
import com.apac.claims.shared.event.EventTypes;
import com.apac.claims.shared.money.MoneyRules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the events of a claim into notifications. It works from the payload of the event alone and never looks a claim
 * up, which is why the module does not depend on the claim module: the payload carries the claim number, type, status,
 * market, the claimant, the assigned officer when there is one, and the amounts, currency, reason code and payment
 * reference of the events they belong to. Texts are short and in plain English and never contain anything from an
 * assessment.
 *
 * <p>Who is told what: {@code ClaimSubmitted} the claimant and every active officer of the market; {@code
 * ClaimAssigned}, {@code InformationRequested}, {@code ClaimApproved}, {@code ClaimRejected} and {@code ClaimSettled}
 * the claimant; {@code InformationProvided} the assigned officer; {@code ClaimEscalated} every active manager and the
 * claimant, who gets a neutral text. Other event types are ignored. A recipient who does not exist (any more) is
 * skipped with a warning, so that one missing account does not make an event fail again and again.
 */
@Component
public class NotificationEventHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventHandler.class);

    /** The rejection reasons in words a claimant understands. */
    private static final Map<String, String> REASONS = Map.of(
            "POLICY_INACTIVE", "the policy was not active",
            "NOT_COVERED", "the loss is not covered by the policy",
            "FRAUD_SUSPECTED", "the claim did not pass our verification checks",
            "INSUFFICIENT_EVIDENCE", "there was not enough evidence",
            "DUPLICATE_CLAIM", "it repeats another claim",
            "INFORMATION_NOT_PROVIDED", "the information we asked for was not provided",
            "OTHER", "for other reasons; please open the claim for details");

    private final NotificationService service;
    private final UserRepository users;

    public NotificationEventHandler(NotificationService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @Override
    public void handle(DomainEvent event) {
        switch (event.eventType()) {
            case EventTypes.CLAIM_SUBMITTED -> submitted(event);
            case EventTypes.CLAIM_ASSIGNED -> toClaimant(event, NotificationType.CLAIM_ASSIGNED, "Claim under review",
                    "A claims officer has started to review claim " + claimNumber(event) + ".");
            case EventTypes.INFORMATION_REQUESTED -> toClaimant(event, NotificationType.INFORMATION_REQUESTED,
                    "More information needed", "We need more information to continue with claim "
                            + claimNumber(event) + ". Please open the claim and answer the question.");
            case EventTypes.INFORMATION_PROVIDED -> informationProvided(event);
            case EventTypes.CLAIM_ESCALATED -> escalated(event);
            case EventTypes.CLAIM_APPROVED -> toClaimant(event, NotificationType.CLAIM_APPROVED, "Claim approved",
                    "Your claim " + claimNumber(event) + " was approved"
                            + amount(event, "approvedAmount").map(text -> " for " + text).orElse("") + ".");
            case EventTypes.CLAIM_REJECTED -> toClaimant(event, NotificationType.CLAIM_REJECTED, "Claim not approved",
                    "Your claim " + claimNumber(event) + " was not approved: " + reason(event) + ".");
            case EventTypes.CLAIM_SETTLED -> toClaimant(event, NotificationType.CLAIM_SETTLED, "Claim paid",
                    "Your claim " + claimNumber(event) + " was paid"
                            + amount(event, "settledAmount").map(text -> ": " + text).orElse("")
                            + text(event, "paymentReference").map(ref -> ". Payment reference " + ref).orElse("")
                            + ".");
            default -> log.debug("No notification rule for event type {}", event.eventType());
        }
    }

    // ---- the rules ------------------------------------------------------------------------------------------------

    private void submitted(DomainEvent event) {
        String number = claimNumber(event);
        Optional<String> amount = amount(event, "claimedAmount");
        toClaimant(event, NotificationType.CLAIM_SUBMITTED, "Claim received", "We received your claim " + number
                + amount.map(text -> " for " + text).orElse("") + ". A claims officer will review it soon.");
        String market = text(event, "marketCode").orElse("");
        String type = text(event, "claimType").map(value -> value.toLowerCase(Locale.ROOT)).orElse("new");
        for (AppUser officer : users.findByRoleAndMarketCodeAndActiveTrue(Role.OFFICER, market)) {
            service.deliver(event.eventId(), officer, number, NotificationType.NEW_CLAIM_IN_QUEUE,
                    "New claim in the queue", "Claim " + number + " (" + type + amount.map(text -> ", " + text)
                            .orElse("") + ") is waiting for review in market " + market + ".");
        }
    }

    private void informationProvided(DomainEvent event) {
        Optional<AppUser> officer = text(event, "assignedOfficerUserId").flatMap(users::findByUserId);
        if (officer.isEmpty()) {
            log.warn("No assigned officer to tell about event {} for claim {}", event.eventId(), claimNumber(event));
            return;
        }
        service.deliver(event.eventId(), officer.get(), claimNumber(event), NotificationType.INFORMATION_PROVIDED,
                "Information received", "The claimant answered the request for information on claim "
                        + claimNumber(event) + ".");
    }

    private void escalated(DomainEvent event) {
        String number = claimNumber(event);
        String market = text(event, "marketCode").orElse("");
        List<AppUser> managers = users.findByRoleAndActiveTrue(Role.MANAGER);
        for (AppUser manager : managers) {
            service.deliver(event.eventId(), manager, number, NotificationType.CLAIM_ESCALATED, "Claim escalated",
                    "Claim " + number + " in market " + market + " needs a manager's decision.");
        }
        toClaimant(event, NotificationType.CLAIM_ESCALATED, "Claim under senior review",
                "Claim " + number + " is being reviewed by a manager.");
    }

    private void toClaimant(DomainEvent event, NotificationType type, String title, String message) {
        Optional<AppUser> claimant = text(event, "claimantUserId").flatMap(users::findByUserId);
        if (claimant.isEmpty()) {
            log.warn("No claimant to tell about event {} for claim {}", event.eventId(), claimNumber(event));
            return;
        }
        service.deliver(event.eventId(), claimant.get(), claimNumber(event), type, title, message);
    }

    // ---- reading the payload -----------------------------------------------------------------------------------------

    /** The claim number is the aggregate id of the event, and the payload repeats it. */
    private static String claimNumber(DomainEvent event) {
        return event.aggregateId();
    }

    private static Optional<String> text(DomainEvent event, String key) {
        Object value = event.payload().get(key);
        return value == null || value.toString().isBlank() ? Optional.empty() : Optional.of(value.toString());
    }

    /** An amount with the decimal places of the event's currency, followed by the currency, for example 43000.00 INR. */
    private static Optional<String> amount(DomainEvent event, String key) {
        Optional<String> value = text(event, key);
        Optional<String> currency = text(event, "currencyCode");
        if (value.isEmpty() || currency.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal amount = MoneyRules.normalize(new BigDecimal(value.get()), currency.get());
        return Optional.of(amount.toPlainString() + " " + currency.get());
    }

    private static String reason(DomainEvent event) {
        String code = text(event, "reasonCode").orElse("OTHER");
        return REASONS.getOrDefault(code, code.toLowerCase(Locale.ROOT).replace('_', ' '));
    }
}
