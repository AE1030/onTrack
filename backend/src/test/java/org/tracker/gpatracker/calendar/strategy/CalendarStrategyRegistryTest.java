package org.tracker.gpatracker.calendar.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.calendar.model.CalendarProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CalendarStrategyRegistryTest {

    @Test
    @DisplayName("resolves each strategy by the provider it claims")
    void resolvesByProvider() {
        CalendarStrategy google = strategyFor(CalendarProvider.GOOGLE);
        CalendarStrategy microsoft = strategyFor(CalendarProvider.MICROSOFT);

        CalendarStrategyRegistry registry = new CalendarStrategyRegistry(List.of(google, microsoft));

        assertThat(registry.forProvider(CalendarProvider.GOOGLE)).isSameAs(google);
        assertThat(registry.forProvider(CalendarProvider.MICROSOFT)).isSameAs(microsoft);
    }

    @Test
    @DisplayName("two strategies claiming one provider fail at construction, not at first use")
    void duplicateProviderFailsFast() {
        List<CalendarStrategy> strategies = List.of(
                strategyFor(CalendarProvider.GOOGLE), strategyFor(CalendarProvider.GOOGLE));

        assertThatThrownBy(() -> new CalendarStrategyRegistry(strategies))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE");
    }

    @Test
    @DisplayName("a provider with no strategy is an error, not a null")
    void missingProviderThrows() {
        CalendarStrategyRegistry registry = new CalendarStrategyRegistry(List.of(strategyFor(CalendarProvider.GOOGLE)));

        assertThatThrownBy(() -> registry.forProvider(CalendarProvider.MICROSOFT))
                .isInstanceOf(IllegalStateException.class);
    }

    private static CalendarStrategy strategyFor(CalendarProvider provider) {
        CalendarStrategy strategy = mock(CalendarStrategy.class);
        when(strategy.provider()).thenReturn(provider);
        return strategy;
    }
}
