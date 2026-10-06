import models.Worker;
import utils.ReportFactory;
import utils.WhatsAppSender;
import utils.WorkerParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads the monthly attendance CSV, writes the Hebrew worker report (.docx) next to it,
 * and sends the report on WhatsApp when {@code whatsapp.properties} is present.
 */
public class Main {
    private static final Path DATA_FOLDER = Path.of("C:\\Users\\bfale\\OneDrive\\Desktop\\excel docs");

    // The current month's sheet, exported from Excel as "CSV UTF-8".
    private static final Path INPUT_CSV = DATA_FOLDER.resolve("1.csv");

    // Looked up in the working directory (the project folder when run from IntelliJ). Not in version control.
    private static final Path WHATSAPP_SETTINGS = Path.of("whatsapp.properties");

    public static void main(String[] args) throws Exception {
        List<Worker> workers = WorkerParser.parseWorkerCsv(INPUT_CSV);
        for (Worker w : workers) {
            System.out.println(w);
        }

        Path report = DATA_FOLDER.resolve("פירוט עובדים " + LocalDate.now() + ".docx");
        ReportFactory.writeReport(workers, report);
        System.out.println("Report written to " + report);

        if (Files.exists(WHATSAPP_SETTINGS)) {
            WhatsAppSender.fromProperties(WHATSAPP_SETTINGS).sendDocx(report);
            System.out.println("Report sent on WhatsApp");
        } else {
            System.out.println("No " + WHATSAPP_SETTINGS.toAbsolutePath() + ", skipping WhatsApp");
        }
    }
}
