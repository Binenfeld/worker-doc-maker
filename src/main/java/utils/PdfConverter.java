package utils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Converts a .docx to PDF with Microsoft Word (which must be installed), so the PDF looks exactly like the
 * document does in Word, right-to-left Hebrew included.
 *
 * <p>Word is driven through PowerShell and COM automation. The Word instance it starts is always closed
 * afterwards, even when the conversion fails; Word windows the user has open are left alone.
 */
public final class PdfConverter {
    // Word normally converts the report in a few seconds; this also covers Word's slow first start.
    private static final long TIMEOUT_SECONDS = 120;

    // The paths are passed in environment variables, which keeps Hebrew file names intact.
    // 17 = wdExportFormatPDF. Open(path, ConfirmConversions = false, ReadOnly = true).
    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue' # progress records would show up as XML in the captured output
            $before = @(Get-Process WINWORD -ErrorAction SilentlyContinue | ForEach-Object Id)
            $word = $null
            try {
                try {
                    $word = New-Object -ComObject Word.Application
                } catch {
                    throw "Microsoft Word could not be started (is it installed?): $($_.Exception.Message)"
                }
                $word.Visible = $false
                $word.DisplayAlerts = 0
                $doc = $word.Documents.Open($env:WDM_DOCX, $false, $true)
                $doc.ExportAsFixedFormat($env:WDM_PDF, 17)
                $doc.Close($false)
            } catch {
                [Console]::Error.WriteLine($_.Exception.Message)
                exit 1
            } finally {
                if ($word) {
                    try { $word.Quit($false) } catch {}
                    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($word)
                }
                # Quit can leave the process running for a moment, or not at all if Word hung; end only the one we started.
                Get-Process WINWORD -ErrorAction SilentlyContinue |
                    Where-Object { $before -notcontains $_.Id } |
                    Stop-Process -Force -ErrorAction SilentlyContinue
            }
            """;

    private PdfConverter() {
    }

    /** Writes {@code docx} as a PDF to {@code pdf}, overwriting it if it exists. */
    public static void convert(Path docx, Path pdf) throws IOException, InterruptedException {
        // Remove an older PDF first, so a failed conversion cannot leave it looking like the result.
        Files.deleteIfExists(pdf);
        Path log = Files.createTempFile("WorkerDocMaker-pdf", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encode(SCRIPT))
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().put("WDM_DOCX", docx.toAbsolutePath().toString());
            builder.environment().put("WDM_PDF", pdf.toAbsolutePath().toString());

            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new IOException("Could not start PowerShell to convert the report to PDF: " + e.getMessage(), e);
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Word did not finish converting " + docx.getFileName() + " to PDF within "
                        + TIMEOUT_SECONDS + " seconds. If a Word window or dialog is open, close it and try again.");
            }
            String output = readLog(log);
            if (process.exitValue() != 0 || !Files.isRegularFile(pdf)) {
                throw new IOException("Converting " + docx.getFileName() + " to PDF failed"
                        + (output.isEmpty() ? "" : ": " + output));
            }
        } finally {
            Files.deleteIfExists(log);
        }
    }

    // PowerShell's -EncodedCommand takes the script as Base64 of its UTF-16LE text, which avoids any quoting issues.
    private static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    // Windows PowerShell writes in the console's code page; the messages that matter here are English.
    private static String readLog(Path log) throws IOException {
        return new String(Files.readAllBytes(log), Charset.defaultCharset()).trim();
    }
}
