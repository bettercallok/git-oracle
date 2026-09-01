package ai.gitoracle.ingestor.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryReplayGuardTest {

    private DeliveryReplayGuard guard;

    @BeforeEach
    void setUp() {
        guard = new DeliveryReplayGuard();
    }

    @Test
    void firstSightingOfADeliveryIdIsNotAReplay() {
        assertThat(guard.isReplay("11111111-1111-1111-1111-111111111111")).isFalse();
    }

    @Test
    void secondSightingOfTheSameDeliveryIdIsAReplay() {
        String deliveryId = "22222222-2222-2222-2222-222222222222";
        assertThat(guard.isReplay(deliveryId)).isFalse();
        assertThat(guard.isReplay(deliveryId)).isTrue();
    }

    @Test
    void differentDeliveryIdsAreIndependent() {
        assertThat(guard.isReplay("aaaa")).isFalse();
        assertThat(guard.isReplay("bbbb")).isFalse();
        // Neither is a replay of the other.
        assertThat(guard.isReplay("aaaa")).isTrue();
        assertThat(guard.isReplay("bbbb")).isTrue();
    }

    @Test
    void nullOrBlankDeliveryIdIsNeverTreatedAsAReplay() {
        // Nothing to key on — HMAC verification is still the real gate for
        // these; a Sentry-style delivery with no equivalent header must not
        // be blocked by this check.
        assertThat(guard.isReplay(null)).isFalse();
        assertThat(guard.isReplay(null)).isFalse();
        assertThat(guard.isReplay("")).isFalse();
        assertThat(guard.isReplay("   ")).isFalse();
    }
}
