package monthly;

import cascade.StationOperation;

/** 水量平衡和月平均发电计算。不可行转移返回 null，不截断泄流或外推实测曲线。 */
public final class MonthlyPhysics {
    private MonthlyPhysics() { }

    enum Rejection {
        LEVEL("水位/汛限"), NEGATIVE_INFLOW("下游总入流为负"),
        RELEASE("负泄流/最大泄流"), ECOLOGY("生态下泄不足"),
        TAILWATER_RANGE("泄流超出尾水曲线覆盖"), GROSS_HEAD("非正毛水头"),
        GUARANTEED_POWER("保证出力不足");

        final String label;
        Rejection(String label) { this.label = label; }
    }

    public static StationOperation evaluate(MonthlyStation station, double startLevel, double endLevel,
                                            double inflow, double days, double ecologicalFlow,
                                            double levelLimit, boolean enforceGuaranteedPower) {
        return evaluate(station, startLevel, endLevel, inflow, days, ecologicalFlow,
                levelLimit, enforceGuaranteedPower, null);
    }

    static StationOperation evaluate(MonthlyStation station, double startLevel, double endLevel,
                                     double inflow, double days, double ecologicalFlow,
                                     double levelLimit, boolean enforceGuaranteedPower, long[] rejected) {
        if (station == null || !Double.isFinite(startLevel) || !Double.isFinite(endLevel)
                || !Double.isFinite(inflow) || !Double.isFinite(days) || days <= 0
                || !Double.isFinite(days * 86_400.0) || !Double.isFinite(ecologicalFlow)
                || ecologicalFlow < 0 || !Double.isFinite(levelLimit)) {
            throw new IllegalArgumentException("月水力计算输入不合法");
        }
        // 月水位上限表示整月持续的运行限制，须同时检查月初与月末。
        if (startLevel < station.minLevel() || startLevel > station.maxLevel()
                || endLevel < station.minLevel() || endLevel > station.maxLevel()
                || startLevel > levelLimit || endLevel > levelLimit) {
            return reject(Rejection.LEVEL, rejected);
        }
        if (inflow < 0) return reject(Rejection.NEGATIVE_INFLOW, rejected);
        double startStorage = station.levelStorage().at(startLevel);
        double endStorage = station.levelStorage().at(endLevel);
        double seconds = days * 86_400.0;
        double release = inflow + (startStorage - endStorage) * 1e8 / seconds;
        if (!Double.isFinite(release) || release < 0 || release > station.maxRelease()) {
            return reject(Rejection.RELEASE, rejected);
        }
        if (release < ecologicalFlow) return reject(Rejection.ECOLOGY, rejected);
        if (release < station.releaseTailwater().minX() || release > station.releaseTailwater().maxX()) {
            return reject(Rejection.TAILWATER_RANGE, rejected);
        }
        double tailwater = station.releaseTailwater().at(release);
        double grossHead = (startLevel + endLevel) / 2.0 - tailwater;
        if (grossHead <= 0 && release > 0) return reject(Rejection.GROSS_HEAD, rejected);
        double head = grossHead - station.headLoss();
        double turbineFlow = 0;
        if (head > 0) {
            double installedFlow = station.installedPowerMw() * 1000.0 / (station.powerCoefficient() * head);
            turbineFlow = Math.min(release, Math.min(station.maxTurbineFlow(), installedFlow));
        }
        double spill = release - turbineFlow;
        double powerMw = head > 0 ? station.powerCoefficient() * turbineFlow * head / 1000.0 : 0;
        if (enforceGuaranteedPower && powerMw < station.guaranteedPowerMw()) {
            return reject(Rejection.GUARANTEED_POWER, rejected);
        }
        double energyMwh = powerMw * days * 24.0;
        if (!Double.isFinite(energyMwh)) throw new IllegalArgumentException("发电量溢出，请检查水力参数");
        double residual = (endStorage - startStorage) * 1e8 - (inflow - release) * seconds;
        return new StationOperation(startLevel, endLevel, startStorage, endStorage,
                inflow, release, turbineFlow, spill, tailwater, head, powerMw, energyMwh, residual);
    }

    private static StationOperation reject(Rejection reason, long[] rejected) {
        if (rejected != null) rejected[reason.ordinal()]++;
        return null;
    }
}
