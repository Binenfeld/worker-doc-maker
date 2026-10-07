package utils;

import models.Worker;
import utils.DocxWriter.Table;
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
 * <p>Document layout. Each heading 1 starts a new page, and the tables under it are laid out two per row
 * (right half, then left half) so they stay narrow:
 * <pre>
 *   Title:      פירוט עובדים
 *   Heading 1:  חברות - סך הכל: amount
 *     Tables:     company | number of workers, alphabetical (except לא משוייך), split over the two halves;
 *                 the left half ends with the total row                                       [blue]
 *   Heading 1:  לא משוייך - סך הכל: amount                                           (new page)
 *     Heading 2 + table, one per country of origin:  country - סך הכל: amount
 *                 name | profession | tenure, one row per worker              [green/orange/teal/burgundy]
 *   Heading 1:  סיכום                                                                (new page)
 *     Heading 2:  עובדים לפי ארץ מוצא  -> table: country | number of workers (all workers)  [purple]
 *     Heading 2:  אינטרויזה            -> table: number of workers on intervisa today      [gold]
 * </pre>
 */
public final class ReportFactory {
    private static final String UNASSIGNED = Worker.UNASSIGNED_COMPANY;
    private static final String NOT_SPECIFIED = "לא צוין";
    private static final String WORKER_COUNT_HEADER = "מספר עובדים";
    private static final String TOTAL = "סך הכל";

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
                .addParagraph("נכון לתאריך " + today + "  |  " + TOTAL + " עובדים: " + workers.size());

        addCompaniesSection(doc, companyToWorkers);
        addUnassignedSection(doc, companyToWorkers);
        addSummarySection(doc, workers);

        doc.save(path);
    }

    // The companies (alphabetical) and how many workers each has: the first half of the list on the right,
    // the rest on the left, ending with the total row.
    private static void addCompaniesSection(DocxWriter doc, Map<String, List<Worker>> companyToWorkers) {
        Map<String, Integer> companyToAmount = new TreeMap<>(HEBREW_ORDER);
        companyToWorkers.forEach((company, list) -> {
            if (!company.equals(UNASSIGNED)) {
                companyToAmount.put(company, list.size());
            }
        });
        int total = companyToAmount.values().stream().mapToInt(Integer::intValue).sum();
        doc.addHeading(1, withTotal("חברות", total));

        List<List<String>> rows = countRows(companyToAmount);
        int rightHalf = (rows.size() + 1) / 2;
        List<String> headers = List.of("חברה", WORKER_COUNT_HEADER);
        doc.addSideBySide(
                new Table(null, TableColors.BLUE, headers, rows.subList(0, rightHalf), null, 5, 2),
                new Table(null, TableColors.BLUE, headers, rows.subList(rightHalf, rows.size()),
                        totalRow(total), 5, 2));
    }

    // לא משוייך in detail: a table per country of origin, with every worker, their profession and tenure,
    // two countries per row.
    private static void addUnassignedSection(DocxWriter doc, Map<String, List<Worker>> companyToWorkers) {
        List<Worker> unassigned = companyToWorkers.getOrDefault(UNASSIGNED, List.of());
        doc.addHeadingOnNewPage(1, withTotal(UNASSIGNED, unassigned.size()));

        Map<String, List<Worker>> byCountry = groupBy(unassigned, Worker::getCountryOfOrigin);
        List<Table> tables = new ArrayList<>();
        int colorIndex = 0;
        for (String country : sortByCountDescending(counts(byCountry)).keySet()) {
            List<Worker> countryWorkers = new ArrayList<>(byCountry.get(country));
            countryWorkers.sort(Comparator.comparing((Worker w) -> orNotSpecified(w.getProfession()), HEBREW_ORDER)
                    .thenComparing(Worker::getName, HEBREW_ORDER));

            List<List<String>> rows = new ArrayList<>();
            for (Worker w : countryWorkers) {
                rows.add(List.of(w.getName(), orNotSpecified(w.getProfession()), orNotSpecified(w.getTenure())));
            }
            TableColors colors = COUNTRY_COLORS.get(colorIndex++ % COUNTRY_COLORS.size());
            tables.add(new Table(withTotal(country, countryWorkers.size()), colors,
                    List.of("שם העובד", "מקצוע", "ותק"), rows, null, 5, 3, 3));
        }
        for (int i = 0; i < tables.size(); i += 2) {
            doc.addSideBySide(tables.get(i), i + 1 < tables.size() ? tables.get(i + 1) : null);
        }
    }

    // Totals per country of origin across all workers (right), and how many are on intervisa (VACATION) today (left).
    private static void addSummarySection(DocxWriter doc, List<Worker> workers) {
        doc.addHeadingOnNewPage(1, "סיכום");

        Map<String, Integer> countryToAmount = sortByCountDescending(counts(groupBy(workers, Worker::getCountryOfOrigin)));
        Table byCountry = new Table("עובדים לפי ארץ מוצא", TableColors.PURPLE, List.of("ארץ מוצא", WORKER_COUNT_HEADER),
                countRows(countryToAmount), totalRow(workers.size()), 5, 2);

        long onIntervisa = workers.stream()
                .filter(w -> w.getState() == Worker.WorkerState.VACATION)
                .count();
        Table intervisa = new Table("אינטרויזה", TableColors.GOLD, List.of("מצב", WORKER_COUNT_HEADER),
                List.of(List.of("באינטרויזה", String.valueOf(onIntervisa))), null, 5, 2);

        doc.addSideBySide(byCountry, intervisa);
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

    // "title - סך הכל: amount", used for every heading that shows a number of workers.
    private static String withTotal(String title, int amount) {
        return title + " - " + TOTAL + ": " + amount;
    }

    // One "key | amount" row per entry, in map order.
    private static List<List<String>> countRows(Map<String, Integer> counts) {
        List<List<String>> rows = new ArrayList<>();
        counts.forEach((key, amount) -> rows.add(List.of(key, String.valueOf(amount))));
        return rows;
    }

    private static List<String> totalRow(int total) {
        return List.of(TOTAL, String.valueOf(total));
    }
}
