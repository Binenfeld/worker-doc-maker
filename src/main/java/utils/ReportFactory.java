package utils;

import models.Worker;
import utils.DocxWriter.TableColors;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Collator;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Builds the Hebrew worker report (.docx).
 *
 * <p>Document layout:
 * <pre>
 *   Title:      פירוט עובדים
 *   Table:      company | number of workers, alphabetical, with a total row (except לא משוייך)  [blue]
 *   Heading 1:  לא משוייך - amount
 *     Heading 2:  country - amount        (one per country of origin)
 *       Table:      name | profession | tenure, one row per worker     [green/orange/teal/burgundy]
 *   Heading 1:  סיכום
 *     Heading 2:  עובדים לפי ארץ מוצא  -> table: country | number of workers (all workers)  [purple]
 *     Heading 2:  אינטרויזה            -> table: number of workers on intervisa today      [gold]
 * </pre>
 */
public final class ReportFactory {
    private static final String UNASSIGNED = Worker.UNASSIGNED_COMPANY;
    private static final String NOT_SPECIFIED = "לא צוין";
    private static final String WORKER_COUNT_HEADER = "מספר עובדים";

    // Hebrew alphabetical order (א-ת); handles spaces, punctuation and final letters properly.
    private static final Collator HEBREW_ORDER = Collator.getInstance(Locale.of("he", "IL"));

    // Each country table under לא משוייך gets the next color in this list.
    private static final List<TableColors> COUNTRY_COLORS =
            List.of(TableColors.GREEN, TableColors.ORANGE, TableColors.TEAL, TableColors.BURGUNDY);

    private ReportFactory() {
    }

    /** Writes the report for the given workers to {@code path}, overwriting any existing file. */
    public static void writeReport(List<Worker> workers, Path path) throws IOException {
        Map<String, List<Worker>> companyToWorkers = groupBy(workers, Worker::getCompany);

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        DocxWriter doc = new DocxWriter(true)
                .addTitle("פירוט עובדים")
                .addParagraph("נכון לתאריך " + today + "  |  סה״כ עובדים: " + workers.size());

        addCompaniesSection(doc, companyToWorkers);
        addUnassignedSection(doc, companyToWorkers);
        addSummarySection(doc, workers);

        doc.save(path);
    }

    // A table of companies (alphabetical) and how many workers each has, with a total row.
    private static void addCompaniesSection(DocxWriter doc, Map<String, List<Worker>> companyToWorkers) {
        Map<String, Integer> companyToAmount = new TreeMap<>(HEBREW_ORDER);
        companyToWorkers.forEach((company, list) -> {
            if (!company.equals(UNASSIGNED)) {
                companyToAmount.put(company, list.size());
            }
        });
        addCountTable(doc, TableColors.BLUE, "חברה", companyToAmount);
    }

    // לא משוייך in detail: a heading per country of origin, with every worker, their profession and tenure under it.
    private static void addUnassignedSection(DocxWriter doc, Map<String, List<Worker>> companyToWorkers) {
        List<Worker> unassigned = companyToWorkers.getOrDefault(UNASSIGNED, List.of());
        doc.addHeading(1, UNASSIGNED + " - " + unassigned.size());

        Map<String, List<Worker>> byCountry = groupBy(unassigned, Worker::getCountryOfOrigin);
        int colorIndex = 0;
        for (String country : sortByCountDescending(counts(byCountry)).keySet()) {
            List<Worker> countryWorkers = new ArrayList<>(byCountry.get(country));
            countryWorkers.sort(Comparator.comparing((Worker w) -> orNotSpecified(w.getProfession()), HEBREW_ORDER)
                    .thenComparing(Worker::getName, HEBREW_ORDER));

            List<List<String>> rows = new ArrayList<>();
            for (Worker w : countryWorkers) {
                rows.add(List.of(w.getName(), orNotSpecified(w.getProfession()), orNotSpecified(w.getTenure())));
            }
            doc.addHeading(2, country + " - " + countryWorkers.size());
            TableColors colors = COUNTRY_COLORS.get(colorIndex++ % COUNTRY_COLORS.size());
            doc.addTable(colors, List.of("שם העובד", "מקצוע", "ותק"), rows, null, 5, 3, 2);
        }
    }

    // Totals per country of origin across all workers, and how many are on intervisa (VACATION) today.
    private static void addSummarySection(DocxWriter doc, List<Worker> workers) {
        doc.addHeading(1, "סיכום");

        doc.addHeading(2, "עובדים לפי ארץ מוצא");
        addCountTable(doc, TableColors.PURPLE, "ארץ מוצא",
                sortByCountDescending(counts(groupBy(workers, Worker::getCountryOfOrigin))));

        long onIntervisa = workers.stream()
                .filter(w -> w.getState() == Worker.WorkerState.VACATION)
                .count();
        doc.addHeading(2, "אינטרויזה");
        doc.addTable(TableColors.GOLD, List.of("מצב", WORKER_COUNT_HEADER),
                List.of(List.of("באינטרויזה", String.valueOf(onIntervisa))), null, 4, 1);
    }

    // ---------- Helpers ----------

    private static String orNotSpecified(String value) {
        return (value == null || value.isBlank()) ? NOT_SPECIFIED : value.trim();
    }

    // Groups workers by a field, keeping file order. Blank values are grouped under "לא צוין".
    private static Map<String, List<Worker>> groupBy(List<Worker> workers, Function<Worker, String> field) {
        Map<String, List<Worker>> groups = new LinkedHashMap<>();
        for (Worker w : workers) {
            groups.computeIfAbsent(orNotSpecified(field.apply(w)), k -> new ArrayList<>()).add(w);
        }
        return groups;
    }

    private static Map<String, Integer> counts(Map<String, List<Worker>> groups) {
        Map<String, Integer> result = new LinkedHashMap<>();
        groups.forEach((key, list) -> result.put(key, list.size()));
        return result;
    }

    // Largest count first; equal counts in Hebrew alphabetical order.
    private static Map<String, Integer> sortByCountDescending(Map<String, Integer> counts) {
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(HEBREW_ORDER)))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    // A two-column table: keyHeader | מספר עובדים, one row per entry (in map order), with a total row.
    private static void addCountTable(DocxWriter doc, TableColors colors, String keyHeader, Map<String, Integer> counts) {
        List<List<String>> rows = new ArrayList<>();
        int total = 0;
        for (var entry : counts.entrySet()) {
            rows.add(List.of(entry.getKey(), String.valueOf(entry.getValue())));
            total += entry.getValue();
        }
        doc.addTable(colors, List.of(keyHeader, WORKER_COUNT_HEADER), rows,
                List.of("סה״כ", String.valueOf(total)), 4, 1);
    }
}
