package utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a .docx to PDF with Microsoft Word (which must be installed), so the PDF looks exactly like the
 * document does in Word, right-to-left Hebrew included.
 *
 * <p>Word is driven through PowerShell and COM automation. The Word instance it starts is always closed
 * afterwards, even when the conversion fails or hangs; Word windows the user has open are left alone.
 */
public final class PdfConverter {
    // Word normally converts the report in a few seconds; this also covers Word's slow first start.
    private static final long TIMEOUT_SECONDS = 120;

    // How long to wait for PowerShell to exit after it has been killed, before reading its log.
    private static final long KILL_WAIT_SECONDS = 10;

    // The script prints "WORDPID=<id>" as soon as it knows which Word process is ours, so Java can stop that
    // Word if the script hangs. That line is not part of any error message.
    private static final Pattern WORD_PID_LINE = Pattern.compile("(?m)^WORDPID=(\\d+)\\s*$");

    // The paths are passed in environment variables, which keeps Hebrew file names intact.
    // Word started through COM has "/Automation" on its command line, which tells it apart from Word windows
    // the user opens; only that process is ever stopped.
    // Open(path, ConfirmConversions = false, ReadOnly = true, AddToRecentFiles = false); 17 = wdExportFormatPDF.
    // The script ends with an explicit exit code: otherwise PowerShell reports the result of its last command.
    private static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue' # progress records would show up as XML in the captured output
            [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
            $before = @(Get-Process WINWORD -ErrorAction SilentlyContinue | ForEach-Object Id)
            $word = $null
            $doc = $null
            $wordPid = $null
            $failed = $false
            try {
                try {
                    $word = New-Object -ComObject Word.Application
                } catch {
                    throw "Microsoft Word could not be started (is it installed?): $($_.Exception.Message)"
                }
                $wordPid = Get-CimInstance Win32_Process -Filter "Name = 'WINWORD.EXE'" |
                    Where-Object { $before -notcontains $_.ProcessId -and $_.CommandLine -like '*/Automation*' } |
                    Select-Object -First 1 -ExpandProperty ProcessId
                if ($wordPid) {
                    [Console]::Out.WriteLine("WORDPID=$wordPid")
                    [Console]::Out.Flush()
                }
                $word.Visible = $false
                $word.DisplayAlerts = 0
                $doc = $word.Documents.Open($env:WDM_DOCX, $false, $true, $false)
                $doc.ExportAsFixedFormat($env:WDM_PDF, 17)
                $doc.Close($false)
            } catch {
                [Console]::Error.WriteLine($_.Exception.Message)
                $failed = $true
            } finally {
                if ($word) {
                    # No arguments: PowerShell cannot pass Quit's by-reference parameters (the document is already closed).
                    try { $word.Quit() } catch {}
                }
                # Word only exits once every COM reference to it is gone.
                foreach ($comObject in @($doc, $word)) {
                    if ($comObject) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($comObject) }
                }
                $doc = $null
                $word = $null
                [GC]::Collect()
                [GC]::WaitForPendingFinalizers()
                if ($wordPid) {
                    # Give Word time to close by itself (killing it mid-shutdown can damage its settings),
                    # then stop it if it is still running.
                    try { Wait-Process -Id $wordPid -Timeout 15 -ErrorAction SilentlyContinue } catch {}
                    try { Stop-Process -Id $wordPid -Force -ErrorAction SilentlyContinue } catch {}
                }
            }
            if ($failed) { exit 1 }
            exit 0
            """;

    private PdfConverter() {
    }

    /** Writes {@code docx} as a PDF to {@code pdf}, overwriting it if it exists. */
    public static void convert(Path docx, Path pdf) throws IOException, InterruptedException {
        // Remove an older PDF first, so a failed conversion cannot leave it looking like the result.
        try {
            Files.deleteIfExists(pdf);
        } catch (FileSystemException e) {
            throw new IOException("Cannot replace " + pdf + " - it is probably open in another program."
                    + " Close it and try again.", e);
        }

        Path log = Files.createTempFile("WorkerDocMaker-pdf", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(powerShell(), "-NoProfile", "-NonInteractive",
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

            boolean finished;
            try {
                finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                stop(process, log);
                throw e;
            }
            if (!finished) {
                stop(process, log);
                throw new IOException("Word did not finish converting " + docx.getFileName() + " to PDF within "
                        + TIMEOUT_SECONDS + " seconds and was stopped. Try again; if it keeps happening, open Word"
                        + " once by hand and close any message it shows (activation, sign-in, safe mode).");
            }

            String output = WORD_PID_LINE.matcher(readLog(log)).replaceAll("").trim();
            if (process.exitValue() != 0 || !Files.isRegularFile(pdf)) {
                throw new IOException("Converting " + docx.getFileName() + " to PDF failed"
                        + (output.isEmpty() ? "" : ": " + output));
            }
        } finally {
            try {
                Files.deleteIfExists(log);
            } catch (IOException ignored) {
                // A leftover log in the temp folder is harmless.
            }
        }
    }

    // Kills the hung PowerShell and the Word it started; that Word is not a child process (COM launched it),
    // so killing PowerShell alone would leave it running.
    private static void stop(Process process, Path log) {
        process.destroyForcibly();
        try {
            process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            Matcher m = WORD_PID_LINE.matcher(readLog(log));
            if (m.find()) {
                ProcessHandle.of(Long.parseLong(m.group(1)))
                        // Make sure the ID still belongs to Word and was not reused by another program.
                        .filter(p -> p.info().command().orElse("").toUpperCase(Locale.ROOT).endsWith("WINWORD.EXE"))
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        } catch (IOException ignored) {
            // No log means the script never got as far as starting Word.
        }
    }

    // The full path, so a different powershell.exe earlier on the PATH is never picked up.
    private static String powerShell() {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return systemRoot + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    }

    // PowerShell's -EncodedCommand takes the script as Base64 of its UTF-16LE text, which avoids any quoting issues.
    private static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    // The script switches its output to UTF-8, so Word's messages that quote Hebrew paths stay readable.
    private static String readLog(Path log) throws IOException {
        return new String(Files.readAllBytes(log), StandardCharsets.UTF_8); // lenient: never throws on odd bytes
    }
}
