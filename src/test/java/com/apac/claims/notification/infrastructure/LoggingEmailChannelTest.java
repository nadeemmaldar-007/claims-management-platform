package com.apac.claims.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.notification.domain.Notification;
import com.apac.claims.notification.domain.NotificationType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** The logging email channel: the masked address, the one line it writes, and that it never throws. */
class LoggingEmailChannelTest {

    private final LoggingEmailChannel channel = new LoggingEmailChannel();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingEmailChannel.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capture() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
    }

    private static AppUser user(String email) {
        return new AppUser("claimant.in", "hash", "Claimant", email, Role.CLAIMANT, null);
    }

    private static Notification notification() {
        return new Notification(null, "CLM-10001", NotificationType.CLAIM_APPROVED, "Claim approved",
                "Your claim CLM-10001 was approved for 43000.00 INR.", "event-1");
    }

    @ParameterizedTest
    @CsvSource({
            "claimant.in@claims-demo.example, c***@claims-demo.example",
            "a@claims-demo.example, a***@claims-demo.example",
            "Anna.Smith@example.org, A***@example.org"})
    void theAddressIsMaskedAfterItsFirstCharacter(String email, String masked) {
        assertThat(LoggingEmailChannel.maskEmail(email)).isEqualTo(masked);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "no-at-sign", "@no-local-part"})
    void anAddressThatCannotBeMaskedIsHiddenCompletely(String email) {
        assertThat(LoggingEmailChannel.maskEmail(email)).isEqualTo("***");
    }

    @Test
    void itWritesOneInfoLineWithTheMaskedAddressTheTypeAndTheClaimButNeitherTitleNorText() {
        channel.send(notification(), user("claimant.in@claims-demo.example"));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent line = appender.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getFormattedMessage()).isEqualTo(
                "Email notification to c***@claims-demo.example type=CLAIM_APPROVED claim=CLM-10001");
        assertThat(line.getFormattedMessage()).doesNotContain("Claim approved").doesNotContain("43000");
    }

    @Test
    void itNeverThrowsEvenWhenTheRecipientIsMissing() {
        assertThatCode(() -> channel.send(notification(), null)).doesNotThrowAnyException();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }
}
