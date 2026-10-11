package monthly;

import cascade.LinearCurve;
import org.apache.poi.ss.usermodel.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 日入流按真实月长聚合。区间来水必须有独立来源或显式假设，不读取zPlanLYX。 */
public final class MonthlyDataReader {
    private static final int[] DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    private static final Set<String> KEYS = Set.of("raw.directory", "calendar.days", "interval.mode",
            "interval.file", "storage.toM3", "power.toMW", "upstream.levelStep", "downstream.levelStep",
            "upstream.headLoss", "downstream.headLoss", "enforceGuaranteedPower", "upstream.terminalLevel",
            "downstream.terminalLevel", "upstream.monthlyLevelLimits", "downstream.monthlyLevelLimits");

    private MonthlyDataReader() { }

    public record DailyReference(int day, int month, double upstreamInflow, double downstreamInflow,
                                 double observedUpstreamRelease) { }

    public record LoadedData(MonthlyProblem problem, List<DailyReference> daily,
                             Map<String, String> settings, double originalUpstreamTerminal,
                             double originalDownstreamTerminal, List<String> dataWarnings) {
        public LoadedData {
            daily = List.copyOf(daily);
            settings = Collections.unmodifiableMap(new TreeMap<>(settings));
            dataWarnings = List.copyOf(dataWarnings);
        }
    }

    public static LoadedData read(Path directory) throws IOException { return read(directory, null); }

    /** override 只覆盖显式列出的设置，其余继承数据目录中的 dispatch.properties。 */
    public static LoadedData read(Path directory, Path override) throws IOException {
        Properties p = new Properties();
        loadSettings(directory.resolve("dispatch.properties"), p);
        if (override != null) loadSettings(override, p);
        if (number(p, "calendar.days") != 365) {
            throw new IOException("本数据适配器仅接受365天平年资料；不能用截断/补日方式处理闰年");
        }
        double storageScale = number(p, "storage.toM3");
        double powerScale = number(p, "power.toMW");
        if (storageScale <= 0 || powerScale <= 0) throw new IOException("单位转换系数必须为正");
        String intervalMode = required(p, "interval.mode");
        if (!Set.of("input5-as-interval", "missing", "zero-assumption", "monthly-net", "daily-upstream-release").contains(intervalMode)) {
            throw new IOException("interval.mode必须为 input5-as-interval、missing、zero-assumption、monthly-net 或 daily-upstream-release");
        }
        boolean guaranteed = bool(p, "enforceGuaranteedPower");
        Path raw = directory.resolve(required(p, "raw.directory"));
        double[] u = column(raw.resolve("resourceData4.xlsx"), 15);
        double[] d = column(raw.resolve("resourceData5.xlsx"), 15);
        List<String> warnings = new ArrayList<>();
        MonthlyStation upper = station(raw, 4, "龙羊峡", "upstream", u, p, storageScale, powerScale,
                u[1], u[0], warnings);
        MonthlyStation lower = station(raw, 5, "拉西瓦", "downstream", d, p, storageScale, powerScale,
                d[1], d[0], warnings);
        double[] input4 = column(raw.resolve("input4.xlsx"), 365);
        double[] input5 = column(raw.resolve("input5.xlsx"), 365);
        double[] eco4 = column(raw.resolve("ecoFlow4.xlsx"), 12);
        double[] eco5 = column(raw.resolve("ecoFlow5.xlsx"), 12);
        double[] limits4 = limits(p, "upstream.monthlyLevelLimits", upper.maxLevel());
        double[] limits5 = limits(p, "downstream.monthlyLevelLimits", lower.maxLevel());
        double[] interval = new double[12];
        Arrays.fill(interval, Double.NaN);
        double[] observedRelease = new double[365];
        Arrays.fill(observedRelease, Double.NaN);
        if (intervalMode.equals("input5-as-interval")) {
            warnings.add("按用户明确确认，input5作为两库之间的区间来水，下库入流=上库优化总出库+input5。附带旧说明称input5为入库量，与本次指定口径不同，复用资料时需核对。");
        } else if (intervalMode.equals("zero-assumption")) {
            Arrays.fill(interval, 0);
            warnings.add("区间来水明确假设为0；这是条件对照，不是由input4/input5推得的真实区间资料。");
        } else if (intervalMode.equals("monthly-net")) {
            interval = monthlyInterval(directory.resolve(required(p, "interval.file")));
        } else if (intervalMode.equals("daily-upstream-release")) {
            observedRelease = column(directory.resolve(required(p, "interval.file")), 365);
            for (double release : observedRelease) if (release < 0) throw new IOException("实际日出库流量必须非负");
            warnings.add("区间量由input5减去同日龙羊峡实际总出库流量还原；假定日时点一致且忽略传播时滞，负值保留为净增损。");
        } else {
            warnings.add("区间来水缺失，输出以空白表示；程序只能给出与区间资料无关的上库约束诊断，不能求解完整梯级。");
        }
        List<DailyReference> daily = new ArrayList<>();
        List<MonthlyInput> months = new ArrayList<>();
        int offset = 0;
        for (int m = 0; m < 12; m++) {
            double sum4 = 0, sum5 = 0, sumRelease = 0;
            for (int j = 0; j < DAYS[m]; j++) {
                int i = offset + j;
                if (input4[i] < 0 || input5[i] < 0) throw new IOException("第" + (i + 1) + "日实测入库流量为负");
                daily.add(new DailyReference(i + 1, m + 1, input4[i], input5[i], observedRelease[i]));
                sum4 += input4[i]; sum5 += input5[i]; sumRelease += observedRelease[i];
            }
            if (intervalMode.equals("daily-upstream-release")) interval[m] = (sum5 - sumRelease) / DAYS[m];
            if (intervalMode.equals("input5-as-interval")) interval[m] = sum5 / DAYS[m];
            if (eco4[m] < 0 || eco5[m] < 0) throw new IOException("生态流量必须非负");
            try {
                months.add(new MonthlyInput(m + 1, DAYS[m], sum4 / DAYS[m], interval[m], sum5 / DAYS[m],
                        eco4[m], eco5[m], limits4[m], limits5[m]));
            } catch (IllegalArgumentException ex) {
                throw new IOException((m + 1) + "月原始资料聚合不合法：" + ex.getMessage(), ex);
            }
            offset += DAYS[m];
        }
        Map<String, String> settings = new TreeMap<>();
        for (String key : p.stringPropertyNames()) settings.put(key, p.getProperty(key));
        try {
            return new LoadedData(new MonthlyProblem(upper, lower, months, guaranteed), daily, settings, u[3], d[3], warnings);
        } catch (IllegalArgumentException ex) {
            throw new IOException("月调度输入无效：" + ex.getMessage(), ex);
        }
    }

    private static double[] monthlyInterval(Path file) throws IOException {
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (rows.size() != 13 || !rows.get(0).replace("\uFEFF", "").equals("month,interval_net_inflow_m3s")) {
            throw new IOException(file + "需要表头month,interval_net_inflow_m3s及12行月份数据");
        }
        double[] values = new double[12];
        for (int i = 0; i < 12; i++) {
            String[] cells = rows.get(i + 1).split(",", -1);
            try {
                if (cells.length != 2 || Integer.parseInt(cells[0].trim()) != i + 1) throw new NumberFormatException();
                values[i] = Double.parseDouble(cells[1].trim());
                if (!Double.isFinite(values[i])) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw new IOException(file + ":" + (i + 2) + "月份须连续1—12，净区间流量须为有限数", ex);
            }
        }
        return values;
    }

    private static MonthlyStation station(Path raw, int id, String name, String prefix, double[] r,
                                          Properties p, double storageScale, double powerScale,
                                          double neededMin, double neededMax, List<String> warnings) throws IOException {
        double[] levels = column(raw.resolve("z" + id + ".xlsx"), -1);
        double[] volumes = column(raw.resolve("v" + id + ".xlsx"), levels.length);
        double[] releases = column(raw.resolve("q" + id + ".xlsx"), -1);
        double[] tails = column(raw.resolve("zTail" + id + ".xlsx"), releases.length);
        for (int i = 0; i < volumes.length; i++) volumes[i] *= storageScale / 1e8;
        double[][] activeStorage = storageSegment(levels, volumes, neededMin, neededMax, id, warnings);
        checkCurve(releases, tails, false, "泄流尾水" + id);
        if (releases[0] < 0 || volumes[0] < 0) throw new IOException(name + "曲线存在负泄流或负库容");
        try {
            return new MonthlyStation(name, r[1], r[0], r[2], optional(p, prefix + ".terminalLevel", r[3]),
                    number(p, prefix + ".levelStep"), r[8], r[4], r[7] * powerScale, r[5], r[6] * powerScale,
                    number(p, prefix + ".headLoss"), new LinearCurve(activeStorage[0], activeStorage[1]), new LinearCurve(releases, tails));
        } catch (IllegalArgumentException ex) {
            throw new IOException(name + "参数无效：" + ex.getMessage(), ex);
        }
    }

    /** 仅使用覆盖所有运行/参考水位的片段及插值邻点；未用区域的原始错值不被猜改。 */
    private static double[][] storageSegment(double[] x, double[] y, double min, double max, int id,
                                              List<String> warnings) throws IOException {
        if (x.length < 2 || x[0] > min || x[x.length - 1] < max) throw new IOException("水位库容" + id + "未覆盖全部参考与运行水位");
        for (int i = 1; i < x.length; i++) if (x[i] <= x[i - 1]) throw new IOException("z" + id + "第" + (i + 1) + "行水位未严格递增");
        int first = 0, last = x.length - 1;
        while (first + 1 < x.length && x[first + 1] <= min) first++;
        while (last - 1 > first && x[last - 1] >= max) last--;
        for (int i = 1; i < y.length; i++) {
            if (y[i] <= y[i - 1] && !(i > first && i <= last)) {
                warnings.add("v" + id + ".xlsx 第" + i + "—" + (i + 1) + "行库容不严格递增，位于本次未用水位段；原值保留。");
            }
        }
        double[] activeX = Arrays.copyOfRange(x, first, last + 1);
        double[] activeY = Arrays.copyOfRange(y, first, last + 1);
        checkCurve(activeX, activeY, true, "水位库容" + id + "（原表起始行" + (first + 1) + "）");
        warnings.add(String.format(Locale.ROOT, "水位库容%d仅使用原表第%d—%d行，覆盖 %.6f—%.6f m 的全部参考与运行水位；含插值邻点。",
                id, first + 1, last + 1, min, max));
        return new double[][]{activeX, activeY};
    }

    private static void checkCurve(double[] x, double[] y, boolean strictY, String label) throws IOException {
        if (x.length < 2) throw new IOException(label + "至少需要两个点");
        for (int i = 1; i < x.length; i++) {
            if (x[i] <= x[i - 1] || (strictY ? y[i] <= y[i - 1] : y[i] < y[i - 1])) {
                throw new IOException(label + "第" + (i + 1) + "行不满足曲线单调性");
            }
        }
    }

    /** 源资料均为无表头第一列；空行、非数值或缺日立即报错，不跳过。 */
    static double[] column(Path file, int expected) throws IOException {
        try (InputStream stream = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(stream)) {
            if (workbook.getNumberOfSheets() != 1) throw new IOException(file + "要求恰好一个工作表");
            Sheet sheet = workbook.getSheetAt(0);
            int n = sheet.getLastRowNum() + 1;
            if (expected >= 0 && n != expected) throw new IOException(file + "需要" + expected + "行，实际" + n + "行");
            double[] values = new double[n];
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int i = 0; i < n; i++) {
                Row row = sheet.getRow(i);
                Cell cell = row == null ? null : row.getCell(0);
                CellValue value = cell == null ? null : evaluator.evaluate(cell);
                if (value == null || value.getCellType() != CellType.NUMERIC || !Double.isFinite(value.getNumberValue())) {
                    throw new IOException(file + "!A" + (i + 1) + "必须为有限数值，不能空缺");
                }
                values[i] = value.getNumberValue();
            }
            return values;
        } catch (IOException ex) { throw ex; }
        catch (RuntimeException ex) { throw new IOException("读取失败：" + file + "：" + ex.getMessage(), ex); }
    }

    private static void loadSettings(Path file, Properties p) throws IOException {
        Properties next = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { next.load(reader); }
        for (String key : next.stringPropertyNames()) {
            if (!KEYS.contains(key)) throw new IOException(file + "未知配置项：" + key);
            p.setProperty(key, next.getProperty(key).trim());
        }
    }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("缺少配置项：" + key);
        return value;
    }
    private static double number(Properties p, String key) throws IOException {
        try {
            double v = Double.parseDouble(required(p, key));
            if (!Double.isFinite(v)) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException ex) { throw new IOException("配置项" + key + "必须为有限数值", ex); }
    }
    private static double optional(Properties p, String key, double fallback) throws IOException {
        return p.containsKey(key) ? number(p, key) : fallback;
    }
    private static boolean bool(Properties p, String key) throws IOException {
        String value = required(p, key);
        if (!value.equals("true") && !value.equals("false")) throw new IOException(key + "必须为true或false");
        return Boolean.parseBoolean(value);
    }
    private static double[] limits(Properties p, String key, double fallback) throws IOException {
        double[] result = new double[12];
        Arrays.fill(result, fallback);
        if (!p.containsKey(key)) return result;
        String[] parts = required(p, key).split(",", -1);
        if (parts.length != 12) throw new IOException(key + "需要12项");
        for (int i = 0; i < 12; i++) {
            try { result[i] = Double.parseDouble(parts[i].trim()); }
            catch (NumberFormatException ex) { throw new IOException(key + "第" + (i + 1) + "项不是数值", ex); }
            if (!Double.isFinite(result[i])) throw new IOException(key + "必须为有限数值");
        }
        return result;
    }
}
