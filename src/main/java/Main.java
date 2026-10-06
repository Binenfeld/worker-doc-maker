import models.Worker;
import utils.FileIOHandler;
import utils.ReportFactory;
import utils.WhatsAppSender;
import utils.WorkerParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Reads the newest attendance CSV in the input folder, writes the Hebrew worker report (.docx) next to it,
 * and sends the report on WhatsApp when {@code whatsapp.properties} is present.
 *
 * <p>Usage: {@code java -jar WorkerDocMaker.jar [inputFolder]} (default {@value #DEFAULT_INPUT_FOLDER}).
 */
public class Main {
    // Where the month's sheet, exported from Excel as "CSV UTF-8", is dropped. The newest .csv there is used.
    private static final String DEFAULT_INPUT_FOLDER = "C:\\workerdocmaker";

    // Looked up in the working directory (the project folder when run from IntelliJ or run.ps1). Not in version control.
    private static final Path WHATSAPP_SETTINGS = Path.of("whatsapp.properties");

    public static void main(String[] args) throws Exception {
        Path inputFolder = Path.of(args.length > 0 ? args[0] : DEFAULT_INPUT_FOLDER);
        Path inputCsv = new FileIOHandler().newestFile(inputFolder, ".csv")
                .orElseThrow(() -> new IllegalStateException(
                        "No .csv file in " + inputFolder.toAbsolutePath() + " (does the folder exist?)"));
        System.out.println("Reading " + inputCsv);

        List<Worker> workers = WorkerParser.parseWorkerCsv(inputCsv);
        for (Worker w : workers) {
            System.out.println(w);
        }

        Path report = inputCsv.resolveSibling("פירוט עובדים " + LocalDate.now() + ".docx");
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
