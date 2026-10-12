package monthly;

import cascade.StationOperation;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.StringJoiner;

/** 每次运行同时输出数据处理证据、结果状态和计算明细；无解时不保留旧的成功结果。 */
public final class MonthlyOutputWriter {
    private MonthlyOutputWriter() { }

    public static void write(Path directory, MonthlyDataReader.LoadedData data,
                             MonthlyResult result, String failure) throws IOException {
        Files.createDirectories(directory);
        writeInputs(directory, data);
        writeSummary(directory.resolve("summary.md"), data, result, failure);
        if (result == null) {
            // 仅清理由本入口生成的固定输出名，避免无解重跑后误读旧调度轨迹。
            Files.deleteIfExists(directory.resolve("dispatch.csv"));
            Files.deleteIfExists(directory.resolve("monthly-summary.csv"));
            Files.deleteIfExists(directory.resolve("charts.png"));
        } else {
            writeDispatch(directory.resolve("dispatch.csv"), result);
            writeCompact(directory.resolve("monthly-summary.csv"), result);
            writeCharts(directory.resolve("charts.png"), result);
        }
    }

    private static void writeInputs(Path directory, MonthlyDataReader.LoadedData data) throws IOException {
        try (BufferedWriter w = writer(directory.resolve("monthly-inputs.csv"))) {
            line(w, "month,days,upstream_observed_inflow_m3s,source_input5_m3s,interval_net_inflow_m3s,upstream_ecological_flow_m3s,downstream_ecological_flow_m3s,upstream_level_limit_m,downstream_level_limit_m");
            for (MonthlyInput p : data.problem().periods()) line(w, numbers(p.month(), p.days(), p.upstreamInflow(),
                    p.sourceInput5(), p.intervalNetInflow(), p.upstreamEcologicalFlow(),
                    p.downstreamEcologicalFlow(), p.upstreamLevelLimit(), p.downstreamLevelLimit()));
        }
        try (BufferedWriter w = writer(directory.resolve("daily-inputs.csv"))) {
            line(w, "day_of_year,month,upstream_observed_inflow_m3s,source_input5_m3s,observed_upstream_release_m3s");
            for (MonthlyDataReader.DailyReference p : data.daily()) line(w, numbers(p.day(), p.month(), p.upstreamInflow(),
                    p.downstreamInflow(), p.observedUpstreamRelease()));
        }
    }

    private static void writeDispatch(Path file, MonthlyResult result) throws IOException {
        String[] columns = {"start_level_m", "end_level_m", "start_storage_1e8m3", "end_storage_1e8m3",
                "inflow_m3s", "release_m3s", "turbine_flow_m3s", "spill_m3s", "tailwater_m", "head_m",
                "power_mw", "energy_mwh", "balance_residual_m3"};
        try (BufferedWriter w = writer(file)) {
            StringJoiner header = new StringJoiner(",");
            header.add("month").add("days").add("interval_net_inflow_m3s");
            for (String prefix : new String[]{"upstream_", "downstream_"}) for (String c : columns) header.add(prefix + c);
            header.add("total_energy_mwh").add("cumulative_energy_mwh").add("coupling_residual_m3s");
            line(w, header.toString());
            double cumulative = 0;
            for (MonthlyPeriodResult p : result.periods()) {
                cumulative += p.upstream().energyMwh() + p.downstream().energyMwh();
                line(w, numbers(p.input().month(), p.input().days(), p.input().intervalNetInflow()) + ","
                        + operation(p.upstream()) + "," + operation(p.downstream()) + ","
                        + numbers(p.upstream().energyMwh() + p.downstream().energyMwh(), cumulative,
                        p.downstream().inflow() - p.upstream().release() - p.input().intervalNetInflow()));
            }
        }
    }

    private static void writeCompact(Path file, MonthlyResult result) throws IOException {
        try (BufferedWriter w = writer(file)) {
            w.write('\uFEFF'); // 便于 Windows Excel 直接识别中文表头。
            line(w, "月份,天数,龙羊峡月初水位m,龙羊峡月末水位m,拉西瓦月初水位m,拉西瓦月末水位m,龙羊峡总泄流m3s,拉西瓦总泄流m3s,龙羊峡出力MW,拉西瓦出力MW,龙羊峡电量亿kWh,拉西瓦电量亿kWh,梯级电量亿kWh");
            for (MonthlyPeriodResult p : result.periods()) {
                StationOperation u = p.upstream(), d = p.downstream();
                line(w, numbers(p.input().month(), p.input().days(), u.startLevel(), u.endLevel(), d.startLevel(),
                        d.endLevel(), u.release(), d.release(), u.powerMw(), d.powerMw(), u.energyMwh() / 1e5,
                        d.energyMwh() / 1e5, (u.energyMwh() + d.energyMwh()) / 1e5));
            }
        }
    }

    private static void writeSummary(Path file, MonthlyDataReader.LoadedData data,
                                     MonthlyResult result, String failure) throws IOException {
        MonthlyProblem problem = data.problem();
        double intervalVolume = problem.periods().stream().mapToDouble(p -> p.intervalNetInflow() * p.seconds()).sum() / 1e8;
        long negativeMonths = problem.periods().stream().filter(p -> p.intervalNetInflow() < 0).count();
        boolean overridden = Math.abs(problem.upper().terminalLevel() - data.originalUpstreamTerminal()) > 1e-8
                || Math.abs(problem.lower().terminalLevel() - data.originalDownstreamTerminal()) > 1e-8;
        try (BufferedWriter w = writer(file)) {
            line(w, "# 龙羊峡—拉西瓦 12 个月调度结果\n");
            line(w, "状态：**" + (result == null ? "当前显式场景未得到可行调度" : "所选离散网格内最优可行调度") + "**。\n");
            line(w, "场景：" + (overridden ? "**显式修改末水位的对照场景，不能作为原参数的可行解。**" : "保留参数表初末水位。") + "\n");
            line(w, format("原始年末水位：龙羊峡 %.3f m，拉西瓦 %.3f m。当前年末水位：龙羊峡 %.3f m，拉西瓦 %.3f m。\n",
                    data.originalUpstreamTerminal(), data.originalDownstreamTerminal(), problem.upper().terminalLevel(), problem.lower().terminalLevel()));
            if (result == null) {
                line(w, "无解诊断：" + failure + "\n");
                line(w, "本次没有生成有效 dispatch.csv。不得把历史文件或对照场景电量标为原参数优化结果。\n");
            } else {
                double up = result.periods().stream().mapToDouble(p -> p.upstream().energyMwh()).sum();
                double down = result.periods().stream().mapToDouble(p -> p.downstream().energyMwh()).sum();
                double residual = result.periods().stream().mapToDouble(p -> Math.max(Math.abs(p.upstream().balanceResidualM3()),
                        Math.abs(p.downstream().balanceResidualM3()))).max().orElseThrow();
                line(w, "| 指标 | 结果 |\n| --- | ---: |");
                line(w, format("| 龙羊峡年发电量 | %.6f 亿 kWh |", up / 1e5));
                line(w, format("| 拉西瓦年发电量 | %.6f 亿 kWh |", down / 1e5));
                line(w, format("| 梯级年发电量 | %.6f 亿 kWh（%.6f MWh） |", result.totalEnergyMwh() / 1e5, result.totalEnergyMwh()));
                line(w, format("| 最大逐库水量平衡残差 | %.3e m³ |", residual));
                line(w, format("| 每月完整联合网格规模 | %d × %d |\n", problem.upper().levels().length, problem.lower().levels().length));
                line(w, "![月末水位与月电量](charts.png)\n");
            }
            line(w, "## 数据口径与限制\n");
            line(w, "365 天按平年 1—12 月分组，不宣称属于某个实际年份。zPlanLYX 按用户要求完全不参与计算。\n");
            line(w, "区间来水来源模式：**" + data.settings().get("interval.mode") + "**。" +
                    (Double.isNaN(intervalVolume) ? "区间资料缺失，不生成梯级电量。" : format("净区间年水量 %.6f 亿 m³；%d 个月为负值（若有则原样保留）。", intervalVolume, negativeMonths)) + "\n");
            if (data.settings().get("interval.mode").equals("input5-as-interval")) {
                line(w, "本次用户确认input5即两库之间的区间来水，直接聚合使用，拉西瓦优化入库 = 龙羊峡优化总出库 + input5月均流量。附带旧说明将input5称为入库流量，与本次指定含义不同，此差异保留以便核实。无需相减还原，不固定下库总入流，不假设区间为0。\n");
            } else {
                line(w, "当前选择了可选区间来源模式，具体含义及假设见有效配置和数据警告。所有模式都采用下库优化入库=上库优化总出库+所选区间量。\n");
            }
            line(w, "原始单位未标注：配置显式给出库容与功率单位转换。保证出力是否作逐月硬约束由配置决定，原资料未给保证率。\n");
            line(w, "月平均模型忽略月内变化、传播时滞、蒸发渗漏的单独建模和机组启停。生态约束在月平均总泄流上实施，不能证明逐日满足。未提供汛限、变幅或损失水头资料；默认正常水位上限和零损失水头。曲线以外不外推，资料不足会限制可计算转移。\n");
            for (String warning : data.dataWarnings()) line(w, "- " + warning);
            line(w, "");
            line(w, "## 本次有效配置\n\n```properties");
            for (var entry : data.settings().entrySet()) line(w, entry.getKey() + "=" + entry.getValue());
            line(w, "```\n\n可追溯输入见 monthly-inputs.csv、daily-inputs.csv；原始文件与 SHA-256 清单见数据目录。详细模型见 docs/longyangxia-laxiwa-monthly.md。");
        }
    }

    private static void writeCharts(Path file, MonthlyResult result) throws IOException {
        DefaultCategoryDataset upper = new DefaultCategoryDataset(), lower = new DefaultCategoryDataset(), energy = new DefaultCategoryDataset();
        upper.addValue(result.periods().get(0).upstream().startLevel(), "Longyangxia", "0");
        lower.addValue(result.periods().get(0).downstream().startLevel(), "Laxiwa", "0");
        for (MonthlyPeriodResult p : result.periods()) {
            String month = Integer.toString(p.input().month());
            upper.addValue(p.upstream().endLevel(), "Longyangxia", month);
            lower.addValue(p.downstream().endLevel(), "Laxiwa", month);
            energy.addValue(p.upstream().energyMwh() / 1000, "Longyangxia", month);
            energy.addValue(p.downstream().energyMwh() / 1000, "Laxiwa", month);
        }
        JFreeChart u = ChartFactory.createLineChart("Longyangxia boundary level", "Month (0 = initial)", "Elevation (m)", upper,
                PlotOrientation.VERTICAL, false, false, false);
        JFreeChart d = ChartFactory.createLineChart("Laxiwa boundary level", "Month (0 = initial)", "Elevation (m)", lower,
                PlotOrientation.VERTICAL, false, false, false);
        ((org.jfree.chart.axis.NumberAxis) u.getCategoryPlot().getRangeAxis()).setAutoRangeIncludesZero(false);
        ((org.jfree.chart.axis.NumberAxis) d.getCategoryPlot().getRangeAxis()).setAutoRangeIncludesZero(false);
        ((org.jfree.chart.axis.NumberAxis) u.getCategoryPlot().getRangeAxis()).setNumberFormatOverride(new java.text.DecimalFormat("0.0"));
        ((org.jfree.chart.axis.NumberAxis) d.getCategoryPlot().getRangeAxis()).setNumberFormatOverride(new java.text.DecimalFormat("0.0"));
        ((org.jfree.chart.axis.NumberAxis) d.getCategoryPlot().getRangeAxis()).setAutoRangeMinimumSize(2.0);
        JFreeChart e = ChartFactory.createBarChart("Monthly energy (selected scenario)", "Month", "Energy (GWh)", energy,
                PlotOrientation.VERTICAL, true, false, false);
        Color blue = new Color(40, 97, 161), teal = new Color(20, 142, 133);
        for (JFreeChart chart : new JFreeChart[]{u, d, e}) {
            chart.setBackgroundPaint(Color.WHITE);
            chart.getCategoryPlot().setBackgroundPaint(new Color(248, 250, 252));
            chart.getCategoryPlot().setRangeGridlinePaint(new Color(214, 222, 230));
            chart.getCategoryPlot().getRenderer().setSeriesPaint(0, chart == d ? teal : blue);
        }
        e.getCategoryPlot().getRenderer().setSeriesPaint(1, teal);
        var bars = (org.jfree.chart.renderer.category.BarRenderer) e.getCategoryPlot().getRenderer();
        bars.setBarPainter(new org.jfree.chart.renderer.category.StandardBarPainter());
        bars.setShadowVisible(false);
        u.getCategoryPlot().getRenderer().setSeriesStroke(0, new BasicStroke(2.5f));
        d.getCategoryPlot().getRenderer().setSeriesStroke(0, new BasicStroke(2.5f));
        BufferedImage image = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1200, 900);
            graphics.drawImage(u.createBufferedImage(600, 400), 0, 0, null);
            graphics.drawImage(d.createBufferedImage(600, 400), 600, 0, null);
            graphics.drawImage(e.createBufferedImage(1200, 500), 0, 400, null);
        } finally { graphics.dispose(); }
        ImageIO.write(image, "png", file.toFile());
    }

    private static String operation(StationOperation p) {
        return numbers(p.startLevel(), p.endLevel(), p.startStorage(), p.endStorage(), p.inflow(), p.release(),
                p.turbineFlow(), p.spill(), p.tailwater(), p.head(), p.powerMw(), p.energyMwh(), p.balanceResidualM3());
    }
    private static BufferedWriter writer(Path file) throws IOException { return Files.newBufferedWriter(file, StandardCharsets.UTF_8); }
    private static void line(BufferedWriter w, String value) throws IOException { w.write(value); w.newLine(); }
    private static String format(String format, Object... values) { return String.format(Locale.ROOT, format, values); }
    private static String numbers(double... values) {
        StringJoiner row = new StringJoiner(",");
        for (double value : values) row.add(Double.isNaN(value) ? "" : format("%.9f", value));
        return row.toString();
    }
}
