package monthly;

import java.util.List;

/** 确定性、月内均匀流量、忽略传播时滞的全年双库联合调度。 */
public record MonthlyProblem(MonthlyStation upper, MonthlyStation lower,
                             List<MonthlyInput> periods, boolean enforceGuaranteedPower) {
    public MonthlyProblem {
        if (upper == null || lower == null || periods == null || periods.size() != 12
                || periods.stream().anyMatch(p -> p == null)) {
            throw new IllegalArgumentException("需要两座电站及完整的 12 个月输入");
        }
        periods = List.copyOf(periods);
        for (int i = 0; i < periods.size(); i++) {
            MonthlyInput p = periods.get(i);
            if (p.month() != i + 1) throw new IllegalArgumentException("月份必须从 1 到 12 连续排列");
            if (p.upstreamLevelLimit() < upper.minLevel() || p.upstreamLevelLimit() > upper.maxLevel()
                    || p.downstreamLevelLimit() < lower.minLevel() || p.downstreamLevelLimit() > lower.maxLevel()) {
                throw new IllegalArgumentException(p.month() + " 月水位上限超出电站运行范围");
            }
        }
        checkGridStorage(upper);
        checkGridStorage(lower);
    }

    private static void checkGridStorage(MonthlyStation station) {
        double previous = Double.NEGATIVE_INFINITY;
        for (double level : station.levels()) {
            double storage = station.levelStorage().at(level);
            if (storage < 0 || storage <= previous) {
                throw new IllegalArgumentException(station.name() + "：网格水位对应库容必须非负且严格递增");
            }
            previous = storage;
        }
    }
}
