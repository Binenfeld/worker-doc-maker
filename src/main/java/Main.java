import models.Worker;
import utils.FileIOHandler;
import utils.PdfConverter;
import utils.ReportFactory;
import utils.UserFacingException;
import utils.WhatsAppSender;
import utils.WorkerParser;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the newest attendance CSV in the input folder, writes the Hebrew worker report (.docx) next to it,
 * converts it to PDF with Word, and sends the PDF on WhatsApp.
 *
 * <p>Usage: {@code java -jar WorkerDocMaker.jar [inputFolder] [--no-whatsapp] [--debug]}
 * (default folder {@value #DEFAULT_INPUT_FOLDER}).
 * <ul>
 *   <li>{@code --no-whatsapp}: write the report and the PDF but don't send anything (for testing)</li>
 *   <li>{@code --debug}: also print every worker read, and the stack trace of any error</li>
 * </ul>
 *
 * <p>Any problem ends the program with exit code 1 and a message saying what went wrong and how to fix it.
 */
public class Main {
    // Where the month's sheet, exported from Excel as "CSV UTF-8", is dropped. The newest .csv there is used.
    private static final String DEFAULT_INPUT_FOLDER = "C:\\workerdocmaker";

    // WhatsApp settings (they hold the access token, so they are never in version control). Looked up first in
    // the input folder, so any copy of the program finds them, then in the working directory (the project
    // folder when run from IntelliJ or run.ps1).
    private static final String WHATSAPP_SETTINGS = "whatsapp.properties";

    private static final String NO_WHATSAPP_FLAG = "--no-whatsapp";
    private static final String DEBUG_FLAG = "--debug";

    public static void main(String[] args) {
        boolean debug = List.of(args).contains(DEBUG_FLAG);
        try {
            run(args, debug);
        } catch (UserFacingException e) {
            printError(e.getMessage(), e, debug);
            System.exit(1);
        } catch (Throwable e) {
            // Not one of the problems the program knows how to explain: a bug, or something unusual on this computer.
            printError("Unexpected error: " + describe(e), e, true);
            System.exit(1);
        }
    }

    private static void run(String[] args, boolean debug) throws Exception {
        boolean sendWhatsApp = true;
        List<String> folders = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case NO_WHATSAPP_FLAG -> sendWhatsApp = false;
                case DEBUG_FLAG -> { }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new UserFacingException("Unknown option \"" + arg + "\". Options: "
                                + NO_WHATSAPP_FLAG + ", " + DEBUG_FLAG + ".");
                    }
                    folders.add(arg);
                }
            }
        }
        if (folders.size() > 1) {
            throw new UserFacingException("Expected at most one input folder, got " + folders.size() + ": " + folders
                    + ". Put a folder name that contains spaces in quotes.");
        }
        Path inputFolder = Path.of(folders.isEmpty() ? DEFAULT_INPUT_FOLDER : folders.getFirst()).toAbsolutePath();
        if (!Files.isDirectory(inputFolder)) {
            throw new UserFacingException("The input folder " + inputFolder + " does not exist. Create it and save"
                    + " the month's sheet there (Excel: File > Save As > CSV UTF-8), or pass a different folder.");
        }

        // Check the WhatsApp settings before doing any work, so a missing or broken file is reported straight away.
        WhatsAppSender sender = null;
        if (sendWhatsApp) {
            Path settings = findWhatsAppSettings(inputFolder).orElseThrow(() -> new UserFacingException(
                    "No " + WHATSAPP_SETTINGS + " found, so the report cannot be sent on WhatsApp.\n"
                            + "Looked in:\n  " + inputFolder.resolve(WHATSAPP_SETTINGS) + "\n  "
                            + Path.of(WHATSAPP_SETTINGS).toAbsolutePath() + "\n"
                            + "The file holds the access token, so it is not in the repository. Copy"
                            + " whatsapp.properties.example to " + inputFolder.resolve(WHATSAPP_SETTINGS)
                            + " and fill it in (see the README).\n"
                            + "To only make the report without sending it, run with " + NO_WHATSAPP_FLAG + "."));
            System.out.println("WhatsApp settings: " + settings);
            sender = WhatsAppSender.fromProperties(settings);
        }

        Path inputCsv = findInputCsv(inputFolder);
        System.out.println("Reading " + inputCsv);
        List<Worker> workers = WorkerParser.parseWorkerCsv(inputCsv);
        System.out.println("Read " + workers.size() + " workers");
        if (debug) {
            workers.forEach(System.out::println);
        }

        String reportName = "פירוט עובדים " + LocalDate.now();
        Path report = inputCsv.resolveSibling(reportName + ".docx");
        try {
            ReportFactory.writeReport(workers, report);
        } catch (FileSystemException e) {
            throw new UserFacingException("Cannot write " + report + " - it is probably open in Word."
                    + " Close it and run again. (" + e.getReason() + ")", e);
        } catch (IOException e) {
            throw new UserFacingException("Cannot write " + report + ": " + describe(e)
                    + ". Check that the folder is writable and the disk is not full.", e);
        }
        System.out.println("Report written to " + report);

        Path pdf = inputCsv.resolveSibling(reportName + ".pdf");
        try {
            PdfConverter.convert(report, pdf);
        } catch (IOException e) {
            throw new UserFacingException(e.getMessage(), e);
        }
        System.out.println("PDF written to " + pdf);

        if (sender == null) {
            System.out.println("Not sending on WhatsApp (" + NO_WHATSAPP_FLAG + ")");
            return;
        }
        sender.sendDocument(pdf);
    }

    private static Optional<Path> findWhatsAppSettings(Path inputFolder) {
        for (Path candidate : List.of(inputFolder.resolve(WHATSAPP_SETTINGS), Path.of(WHATSAPP_SETTINGS))) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath());
            }
        }
        return Optional.empty();
    }

    private static Path findInputCsv(Path inputFolder) throws UserFacingException, IOException {
        FileIOHandler files = new FileIOHandler();
        Optional<Path> csv = files.newestFile(inputFolder, ".csv");
        if (csv.isPresent()) {
            return csv.get();
        }
        boolean hasWorkbook = files.newestFile(inputFolder, ".xlsb").isPresent()
                || files.newestFile(inputFolder, ".xlsx").isPresent();
        throw new UserFacingException("No .csv file in " + inputFolder + "."
                + (hasWorkbook ? " There is an Excel workbook there, but the program reads CSV only." : "")
                + " Open the workbook, select the month's sheet, and use File > Save As > \"CSV UTF-8 (Comma"
                + " delimited)\" to save it in this folder.");
    }

    private static void printError(String message, Throwable e, boolean withStackTrace) {
        System.out.flush();
        System.err.println();
        System.err.println("ERROR: " + message);
        if (withStackTrace) {
            System.err.println();
            e.printStackTrace();
        }
        System.err.flush();
    }

    // "message (ExceptionType)", or just the type when there is no message (e.g. a NullPointerException).
    private static String describe(Throwable e) {
        String type = e.getClass().getSimpleName();
        return e.getMessage() == null || e.getMessage().isBlank() ? type : e.getMessage() + " (" + type + ")";
    }
}
