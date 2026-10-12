package monthly;

import cascade.LinearCurve;

import java.util.TreeSet;

/** 月调度电站参数。水位 m、库容亿 m³、流量 m³/s、出力 MW。 */
public record MonthlyStation(
        String name, double minLevel, double maxLevel,
        double initialLevel, double terminalLevel, double levelStep,
        double maxRelease, double maxTurbineFlow, double installedPowerMw,
        double powerCoefficient, double guaranteedPowerMw, double headLoss,
        LinearCurve levelStorage, LinearCurve releaseTailwater) {

    public MonthlyStation {
        if (name == null || name.isBlank() || levelStorage == null || releaseTailwater == null) {
            throw new IllegalArgumentException("电站名称及水位库容、流量尾水曲线不能为空");
        }
        double[] values = {minLevel, maxLevel, initialLevel, terminalLevel, levelStep,
                maxRelease, maxTurbineFlow, installedPowerMw, powerCoefficient, guaranteedPowerMw, headLoss};
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException(name + "：参数必须为有限数");
        }
        if (maxLevel <= minLevel || initialLevel < minLevel || initialLevel > maxLevel
                || terminalLevel < minLevel || terminalLevel > maxLevel || levelStep <= 0
                || maxRelease <= 0 || maxTurbineFlow <= 0 || installedPowerMw <= 0
                || powerCoefficient <= 0 || guaranteedPowerMw < 0 || guaranteedPowerMw > installedPowerMw
                || headLoss < 0 || (maxLevel - minLevel) / levelStep > 100_000) {
            throw new IllegalArgumentException(name + "：水位范围、初末边界、步长或出力参数不合法");
        }
        if (levelStorage.minX() > minLevel || levelStorage.maxX() < maxLevel
                || levelStorage.at(minLevel) < 0 || levelStorage.at(maxLevel) <= levelStorage.at(minLevel)) {
            throw new IllegalArgumentException(name + "：水位库容曲线必须覆盖运行水位且库容非负递增");
        }
        if (releaseTailwater.maxX() < 0 || releaseTailwater.minX() > maxRelease) {
            throw new IllegalArgumentException(name + "：流量尾水曲线与非负允许泄流范围无交集");
        }
        // 尾水曲线可以覆盖少于 maxRelease 的范围；超出实测覆盖的转移在物理层拒绝。
    }

    /** 保留原始初末水位，不把实测小数边界四舍五入到常规网格。 */
    public double[] levels() {
        TreeSet<Double> levels = new TreeSet<>();
        levels.add(minLevel);
        int steps = (int) Math.floor((maxLevel - minLevel) / levelStep);
        for (int i = 1; i <= steps; i++) {
            double level = minLevel + i * levelStep;
            if (level < maxLevel && Math.abs(level - initialLevel) > 1e-9
                    && Math.abs(level - terminalLevel) > 1e-9 && Math.abs(level - maxLevel) > 1e-9) {
                levels.add(level);
            }
        }
        levels.add(maxLevel);
        levels.add(initialLevel);
        levels.add(terminalLevel);
        return levels.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public int indexOf(double level) {
        int index = java.util.Arrays.binarySearch(levels(), level);
        if (index < 0) throw new IllegalArgumentException(name + "：水位不在离散网格：" + level);
        return index;
    }
}
