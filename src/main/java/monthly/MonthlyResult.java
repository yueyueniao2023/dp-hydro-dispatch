package monthly;

import java.util.List;

public record MonthlyResult(List<MonthlyPeriodResult> periods, double totalEnergyMwh) {
    public MonthlyResult {
        periods = List.copyOf(periods);
        if (!Double.isFinite(totalEnergyMwh) || totalEnergyMwh < 0) {
            throw new IllegalArgumentException("年发电量必须为有限非负数");
        }
    }
}
