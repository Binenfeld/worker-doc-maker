package utils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Line-oriented file utilities. Defaults to UTF-8 unless a charset is given.
 */
public final class FileIOHandler {
    private final Charset charset;

    public FileIOHandler() {
        this(StandardCharsets.UTF_8);
    }

    public FileIOHandler(Charset charset) {
        this.charset = charset;
    }

    // ---------- Reading ----------

    /** Opens a reader for streaming line-by-line with {@link BufferedReader#readLine()}. Caller must close it. */
    public BufferedReader openReader(Path path) throws IOException {
        return Files.newBufferedReader(path, charset);
    }

    /** Reads the first line of the file, or null if the file is empty. */
    public String readFirstLine(Path path) throws IOException {
        try (BufferedReader reader = openReader(path)) {
            return reader.readLine();
        }
    }

    /** Reads the line at the given zero-based index, or null if the file has fewer lines. */
    public String readLine(Path path, int lineIndex) throws IOException {
        if (lineIndex < 0) {
            throw new IllegalArgumentException("lineIndex must be >= 0");
        }
        try (BufferedReader reader = openReader(path)) {
            String line;
            int current = 0;
            while ((line = reader.readLine()) != null) {
                if (current++ == lineIndex) {
                    return line;
                }
            }
            return null;
        }
    }

    /** Reads all lines into memory. */
    public List<String> readAllLines(Path path) throws IOException {
        return Files.readAllLines(path, charset);
    }

    /** Reads all lines, skipping blank ones and trimming whitespace. */
    public List<String> readNonBlankLines(Path path) throws IOException {
        try (Stream<String> lines = Files.lines(path, charset)) {
            return lines.map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    /** Streams each line to the consumer without loading the whole file. */
    public void forEachLine(Path path, Consumer<String> action) throws IOException {
        try (BufferedReader reader = openReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                action.accept(line);
            }
        }
    }

    /** Reads the entire file as a single string. */
    public String readString(Path path) throws IOException {
        return Files.readString(path, charset);
    }

    /** Returns the number of lines in the file (0 for an empty file). */
    public long numberOfLines(Path path) throws IOException {
        try (Stream<String> lines = Files.lines(path, charset)) {
            return lines.count();
        }
    }

    // ---------- Writing ----------

    /** Writes the string to the file, creating or overwriting it. */
    public void writeString(Path path, String content) throws IOException {
        ensureParentDirectories(path);
        Files.writeString(path, content, charset);
    }

    /** Writes the lines to the file, creating or overwriting it. */
    public void writeLines(Path path, List<String> lines) throws IOException {
        ensureParentDirectories(path);
        Files.write(path, lines, charset);
    }

    /** Appends a single line (with a trailing line separator), creating the file if needed. */
    public void appendLine(Path path, String line) throws IOException {
        appendLines(path, List.of(line));
    }

    /** Appends lines, creating the file if needed. */
    public void appendLines(Path path, List<String> lines) throws IOException {
        ensureParentDirectories(path);
        Files.write(path, lines, charset, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Opens a writer that overwrites the file. Caller must close it. */
    public BufferedWriter openWriter(Path path) throws IOException {
        ensureParentDirectories(path);
        return Files.newBufferedWriter(path, charset);
    }

    // ---------- File management ----------

    public boolean exists(Path path) {
        return Files.exists(path);
    }

    public boolean isReadableFile(Path path) {
        return Files.isRegularFile(path) && Files.isReadable(path);
    }

    /** Creates the file (and parent directories) if it does not already exist. */
    public void createFileIfMissing(Path path) throws IOException {
        if (!Files.exists(path)) {
            ensureParentDirectories(path);
            Files.createFile(path);
        }
    }

    /** Creates any missing parent directories of the given path. */
    public void ensureParentDirectories(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** Deletes the file if it exists; returns true if something was deleted. */
    public boolean deleteIfExists(Path path) throws IOException {
        return Files.deleteIfExists(path);
    }

    public void copy(Path source, Path target) throws IOException {
        ensureParentDirectories(target);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void move(Path source, Path target) throws IOException {
        ensureParentDirectories(target);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Lists the regular files directly inside the directory. */
    public List<Path> listFiles(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).toList();
        }
    }

    /**
     * Returns the most recently modified file directly inside the directory whose name ends with the given
     * extension (case-insensitive, e.g. ".csv"), or empty if there is none or the directory does not exist.
     * Office lock files ("~$name") are ignored.
     */
    public Optional<Path> newestFile(Path directory, String extension) throws IOException {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        String suffix = extension.toLowerCase(Locale.ROOT);
        return listFiles(directory).stream()
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.toLowerCase(Locale.ROOT).endsWith(suffix) && !name.startsWith("~$");
                })
                .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
    }

    public Charset getCharset() {
        return charset;
    }
}
