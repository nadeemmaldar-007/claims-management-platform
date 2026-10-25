package com.apac.claims.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.apac.claims.claim.domain.ClaimCounter;
import com.apac.claims.claim.domain.ClaimCounterRepository;
import com.apac.claims.claim.domain.ClaimType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The logic of the generator without a database: the format, the increment, and the broken-reference-data case. */
@ExtendWith(MockitoExtension.class)
class ClaimNumberGeneratorUnitTest {

    @Mock
    private ClaimCounterRepository counters;
    @InjectMocks
    private ClaimNumberGenerator generator;

    @Test
    void aMotorNumberUsesTheCurrentValueAndMovesTheCounterOn() {
        ClaimCounter counter = new ClaimCounter(ClaimType.MOTOR, 10001L);
        when(counters.findForUpdate(ClaimType.MOTOR)).thenReturn(Optional.of(counter));

        String number = generator.next(ClaimType.MOTOR);

        assertThat(number).isEqualTo("CLM-10001");
        assertThat(counter.getNextValue()).isEqualTo(10002L);
        verify(counters).save(counter);
    }

    @Test
    void aPropertyNumberUsesTheClpPrefix() {
        ClaimCounter counter = new ClaimCounter(ClaimType.PROPERTY, 20457L);
        when(counters.findForUpdate(ClaimType.PROPERTY)).thenReturn(Optional.of(counter));

        assertThat(generator.next(ClaimType.PROPERTY)).isEqualTo("CLP-20457");
    }

    @Test
    void aMissingCounterRowIsAClearIllegalStateException() {
        when(counters.findForUpdate(ClaimType.MOTOR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> generator.next(ClaimType.MOTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim counter for MOTOR is missing");
    }
}
