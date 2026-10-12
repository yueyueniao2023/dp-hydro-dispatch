package monthly;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MonthlyDataReaderTest {
    private static final Path DATA = Path.of("data/longyangxia-laxiwa");
    @TempDir Path temp;

    @Test void dailyAggregationPreservesBothObservedVolumesAndDoesNotInventInterval() throws IOException {
        var data = MonthlyDataReader.read(DATA);
        int[] days = {31,28,31,30,31,30,31,31,30,31,30,31};
        assertEquals(365, data.daily().size());
        int offset = 0;
        for (int m = 0; m < 12; m++) {
            var p = data.problem().periods().get(m);
            assertEquals(days[m], p.days());
            double up = 0, down = 0;
            for (int j = 0; j < days[m]; j++) {
                var day = data.daily().get(offset + j);
                assertEquals(offset+j+1, day.day());
                assertEquals(m+1, day.month());
                up += day.upstreamInflow()*86400;
                down += day.downstreamInflow()*86400;
            }
            assertEquals(up, p.upstreamInflow()*p.seconds(), 1e-4);
            assertEquals(down, p.sourceInput5()*p.seconds(), 1e-4);
            assertEquals(p.sourceInput5(), p.intervalNetInflow());
            offset += days[m];
        }
        assertEquals(222.660576, data.problem().periods().stream().mapToDouble(p -> p.upstreamInflow()*p.seconds()/1e8).sum(), 1e-8);
        assertEquals(236.224512, data.problem().periods().stream().mapToDouble(p -> p.sourceInput5()*p.seconds()/1e8).sum(), 1e-8);
    }

    @Test void removingPlanFileHasNoEffectAndChangingTerminalDoesNotInventInterval() throws IOException {
        copyData();
        Files.delete(temp.resolve("raw/zPlanLYX.xlsx"));
        var original = MonthlyDataReader.read(temp);
        var modified = MonthlyDataReader.read(temp, settings("upstream.terminalLevel=2595.2\n"));
        assertEquals(2600, original.problem().upper().terminalLevel());
        assertEquals(2595.2, modified.problem().upper().terminalLevel());
        assertEquals(original.daily(), modified.daily());
        assertEquals(original.problem().periods(), modified.problem().periods());
        var missing = MonthlyDataReader.read(temp, settings("upstream.terminalLevel=2595.2\ninterval.mode=missing\n"));
        assertTrue(assertThrows(IllegalStateException.class, () -> new MonthlyDp(missing.problem()).solve()).getMessage().contains("缺少区间来水"));
        assertTrue(assertThrows(IllegalStateException.class, () -> new MonthlyDp(original.problem()).solve()).getMessage().contains("2598.290707"));
    }

    @Test void explicitlySelectedZeroAssumptionGivesConservingTwelveMonthResult() throws IOException {
        var data = MonthlyDataReader.read(DATA, settings("interval.mode=zero-assumption\nupstream.terminalLevel=2595.2\n"));
        var result = new MonthlyDp(data.problem()).solve();
        assertEquals(12, result.periods().size());
        for (var p : result.periods()) {
            assertEquals(p.upstream().release(), p.downstream().inflow(), 1e-9);
            assertEquals(0, p.upstream().balanceResidualM3(), 1e-5);
            assertEquals(0, p.downstream().balanceResidualM3(), 1e-5);
        }
        assertEquals(2595.2, result.periods().get(11).upstream().endLevel());
    }

    @Test void externalMonthlyNetDataRetainNegativeValues() throws IOException {
        StringBuilder csv = new StringBuilder("month,interval_net_inflow_m3s\n");
        for (int m = 1; m <= 12; m++) csv.append(m).append(',').append(m == 6 ? -5 : 10).append('\n');
        Path file = Files.writeString(temp.resolve("net.csv"), csv);
        var data = MonthlyDataReader.read(DATA, settings("interval.mode=monthly-net\ninterval.file=" + portable(file) + "\n"));
        assertEquals(-5, data.problem().periods().get(5).intervalNetInflow());
        assertEquals(10, data.problem().periods().get(0).intervalNetInflow());
    }

    @Test void actualOutflowIsSubtractedRatherThanUpstreamInflow() throws IOException {
        Path file = temp.resolve("actual.xlsx");
        column(file, 365, -1, 600);
        var data = MonthlyDataReader.read(DATA, settings("interval.mode=daily-upstream-release\ninterval.file="+portable(file)+"\n"));
        for (var p : data.problem().periods()) assertEquals(p.sourceInput5()-600, p.intervalNetInflow(), 1e-10);
    }

    @Test void relevantCurveDefectsFailButUnusedDefectsAreReported() throws IOException {
        assertTrue(MonthlyDataReader.read(DATA).dataWarnings().stream().anyMatch(s -> s.contains("369")));
        copyData();
        Path file = temp.resolve("raw/v4.xlsx");
        Workbook book;
        try (var in = Files.newInputStream(file)) { book = WorkbookFactory.create(in); }
        try (book) {
            book.getSheetAt(0).getRow(650).getCell(0).setCellValue(1);
            try (var out = Files.newOutputStream(file)) { book.write(out); }
        }
        assertTrue(assertThrows(IOException.class, () -> MonthlyDataReader.read(temp)).getMessage().contains("单调性"));
    }

    @Test void badSettingsOrMissingIntervalRowsAreRejected() throws IOException {
        assertThrows(IOException.class, () -> MonthlyDataReader.read(DATA, settings("upstream.terminalLevell=2595.2\n")));
        Path file = Files.writeString(temp.resolve("bad.csv"), "month,interval_net_inflow_m3s\n1,0\n");
        assertThrows(IOException.class, () -> MonthlyDataReader.read(DATA, settings("interval.mode=monthly-net\ninterval.file="+portable(file)+"\n")));
    }

    @ParameterizedTest @ValueSource(ints={364,366})
    void wrongDailyRowCountIsNotSilentlyFixed(int rows) throws IOException {
        copyData();
        column(temp.resolve("raw/input4.xlsx"), rows, -1, 100);
        var error = assertThrows(IOException.class, () -> MonthlyDataReader.read(temp));
        assertTrue(error.getMessage().contains("365"));
        assertTrue(error.getMessage().contains("input4.xlsx"));
    }

    @Test void missingDayRetainsSourceCellLocation() throws IOException {
        Path file = temp.resolve("missing.xlsx");
        column(file, 365, 10, 100);
        assertTrue(assertThrows(IOException.class, () -> MonthlyDataReader.column(file,365)).getMessage().contains("!A11"));
    }

    private Path settings(String text) throws IOException { return Files.writeString(temp.resolve("override.properties"),text); }
    private static String portable(Path p) { return p.toString().replace('\\','/'); }
    private void copyData() throws IOException {
        Files.copy(DATA.resolve("dispatch.properties"),temp.resolve("dispatch.properties"));
        Path raw = Files.createDirectory(temp.resolve("raw"));
        try(var files=Files.list(DATA.resolve("raw"))) { for(Path f:files.toList()) Files.copy(f,raw.resolve(f.getFileName())); }
    }
    private static void column(Path file,int count,int missing,double value) throws IOException {
        try(Workbook book=new XSSFWorkbook()) {
            var sheet=book.createSheet("data");
            for(int i=0;i<count;i++) if(i!=missing) sheet.createRow(i).createCell(0).setCellValue(value);
            try(var out=Files.newOutputStream(file)) { book.write(out); }
        }
    }
}
