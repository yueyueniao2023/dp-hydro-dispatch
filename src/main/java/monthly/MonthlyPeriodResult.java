package monthly;

import cascade.StationOperation;

public record MonthlyPeriodResult(MonthlyInput input, StationOperation upstream, StationOperation downstream) {
    public double energyMwh() { return upstream.energyMwh() + downstream.energyMwh(); }
}
