package com.network.routing;

import com.network.domain.BinRange;
import com.network.domain.Participant;
import com.network.repository.BinRangeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class BinRouterTest {

    private final BinRangeRepository repo = Mockito.mock(BinRangeRepository.class);
    private final BinRouter router = new BinRouter(repo);

    @Test
    void route_returnsIssuer_whenBinMatches() {
        Participant issuer = new Participant();
        BinRange range = new BinRange();
        range.setLow("400000");
        range.setHigh("499999");
        range.setIssuer(issuer);

        when(repo.findByPan("4111111111111111")).thenReturn(Optional.of(range));

        Optional<Participant> result = router.route("4111111111111111");
        assertThat(result).isPresent();
        assertThat(result.get()).isSameAs(issuer);
    }

    @Test
    void route_returnsEmpty_whenNoBinMatch() {
        when(repo.findByPan("9999999999999999")).thenReturn(Optional.empty());
        assertThat(router.route("9999999999999999")).isEmpty();
    }

    @Test
    void route_returnsEmpty_forShortPan() {
        assertThat(router.route("41111")).isEmpty();
    }

    @Test
    void route_returnsEmpty_forNullPan() {
        assertThat(router.route(null)).isEmpty();
    }
}
