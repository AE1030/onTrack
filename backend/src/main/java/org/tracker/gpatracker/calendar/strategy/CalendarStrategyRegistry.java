package org.tracker.gpatracker.calendar.strategy;

import org.springframework.stereotype.Component;
import org.tracker.gpatracker.calendar.model.CalendarProvider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@link CalendarStrategy} for a provider.
 *
 * <p>Built once from every strategy bean Spring finds. Two strategies claiming the same provider
 * fail startup here, rather than one silently shadowing the other at runtime.
 */
@Component
public class CalendarStrategyRegistry {

    private final Map<CalendarProvider, CalendarStrategy> byProvider = new EnumMap<>(CalendarProvider.class);

    public CalendarStrategyRegistry(List<CalendarStrategy> strategies) {
        for (CalendarStrategy strategy : strategies) {
            CalendarStrategy previous = byProvider.put(strategy.provider(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Two calendar strategies claim " + strategy.provider() + ": "
                        + previous.getClass().getSimpleName() + " and " + strategy.getClass().getSimpleName());
            }
        }
    }

    /**
     * @throws IllegalStateException if no strategy is registered for the provider, which means an
     *         account exists for a provider whose integration has not been built or was removed
     */
    public CalendarStrategy forProvider(CalendarProvider provider) {
        CalendarStrategy strategy = byProvider.get(provider);
        if (strategy == null) {
            throw new IllegalStateException("No calendar strategy registered for " + provider);
        }
        return strategy;
    }
}
