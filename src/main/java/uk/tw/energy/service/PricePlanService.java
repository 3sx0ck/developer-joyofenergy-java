package uk.tw.energy.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import uk.tw.energy.domain.ElectricityReading;
import uk.tw.energy.domain.PricePlan;

@Service
public class PricePlanService {

    private final List<PricePlan> pricePlans;
    private final MeterReadingService meterReadingService;
    private final AccountService accountService;

    public PricePlanService(List<PricePlan> pricePlans, MeterReadingService meterReadingService, AccountService accountService) {
        this.pricePlans = pricePlans;
        this.meterReadingService = meterReadingService;
        this.accountService = accountService;
    }

    public Optional<Map<String, BigDecimal>> getConsumptionCostOfElectricityReadingsForEachPricePlan(
            String smartMeterId) {
        Optional<List<ElectricityReading>> electricityReadings = meterReadingService.getReadings(smartMeterId);

        if (!electricityReadings.isPresent()) {
            return Optional.empty();
        }

        return Optional.of(pricePlans.stream()
                .collect(Collectors.toMap(PricePlan::getPlanName, t -> calculateCost(electricityReadings.get(), t))));
    }

    /**
     * Calculates the last week cost for a smart meter id, using the correct price plan and the correct formula.
     */
    public BigDecimal calculateLastWeekCost(String smartMeterId) {
        Optional<List<ElectricityReading>> maybeReadings = meterReadingService.getReadings(smartMeterId);
        if (!maybeReadings.isPresent() || maybeReadings.get().size() < 2) {
            return BigDecimal.ZERO;
        }
        List<ElectricityReading> readings = maybeReadings.get();
        // Only readings from the last 7 days
        Instant oneWeekAgo = Instant.now().minus(Duration.ofDays(7));
        List<ElectricityReading> lastWeekReadings = readings.stream()
                .filter(r -> r.time().isAfter(oneWeekAgo))
                .sorted(Comparator.comparing(ElectricityReading::time))
                .collect(Collectors.toList());
        if (lastWeekReadings.size() < 2) {
            return BigDecimal.ZERO;
        }
        // Average reading
        BigDecimal sum = lastWeekReadings.stream().map(ElectricityReading::reading).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(lastWeekReadings.size()), 10, RoundingMode.HALF_UP);
        // Usage time in hours
        long seconds = Duration.between(lastWeekReadings.get(0).time(), lastWeekReadings.get(lastWeekReadings.size() - 1).time()).getSeconds();
        BigDecimal hours = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 10, RoundingMode.HALF_UP);
        if (hours.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        // Energy consumed
        BigDecimal energyConsumed = average.multiply(hours);
        // Get price plan
        String planId = accountService.getPricePlanIdForSmartMeterId(smartMeterId);
        if (planId == null) {
            throw new IllegalArgumentException("No price plan attached to smart meter id");
        }
        PricePlan plan = pricePlans.stream().filter(p -> p.getPlanName().equals(planId)).findFirst().orElse(null);
        if (plan == null) {
            throw new IllegalArgumentException("No price plan found for id: " + planId);
        }
        // Cost
        return energyConsumed.multiply(plan.getUnitRate()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateCost(List<ElectricityReading> electricityReadings, PricePlan pricePlan) {
        final BigDecimal averageReadingInKw = calculateAverageReading(electricityReadings);
        final BigDecimal usageTimeInHours = calculateUsageTimeInHours(electricityReadings);
        final BigDecimal energyConsumedInKwH = averageReadingInKw.divide(usageTimeInHours, RoundingMode.HALF_UP);
        final BigDecimal cost = energyConsumedInKwH.multiply(pricePlan.getUnitRate());
        return cost;
    }

    public BigDecimal calculateLastWeekCost(List<ElectricityReading> electricityReadings) {
        if (electricityReadings == null || electricityReadings.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (electricityReadings.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalCost = electricityReadings.stream()
                .filter(reading -> reading.time().isAfter(Instant.now().minus(Duration.ofDays(7))))
                .map(ElectricityReading::reading)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return totalCost.divide(BigDecimal.valueOf(electricityReadings.size()), RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAverageReading(List<ElectricityReading> electricityReadings) {
        BigDecimal summedReadings = electricityReadings.stream()
                .map(ElectricityReading::reading)
                .reduce(BigDecimal.ZERO, (reading, accumulator) -> reading.add(accumulator));

        return summedReadings.divide(BigDecimal.valueOf(electricityReadings.size()), RoundingMode.HALF_UP);
    }

    private BigDecimal calculateUsageTimeInHours(List<ElectricityReading> electricityReadings) {
        ElectricityReading first = electricityReadings.stream()
                .min(Comparator.comparing(ElectricityReading::time))
                .get();

        ElectricityReading last = electricityReadings.stream()
                .max(Comparator.comparing(ElectricityReading::time))
                .get();

        return BigDecimal.valueOf(Duration.between(first.time(), last.time()).getSeconds() / 3600.0);
    }
}
