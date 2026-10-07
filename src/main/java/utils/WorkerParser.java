package utils;

import models.Worker;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads workers from the monthly attendance sheet, exported from Excel as "CSV UTF-8".
 *
 * <p>Column layout (0-based), as in the sheet's header row:
 * <pre>
 *   0 כתובת     address / location
 *   1 הסעות     transport (not used)
 *   2 מקצוע     profession
 *   3 אתר       site for assigned workers; tenure ("חודש", "חצי שנה", "שנתיים"...) for unassigned ones
 *   4 שם חברה   company
 *   5 עובד      Andromeda worker ID
 *   6 מוצא      country of origin
 *   7 שם עובד   name
 *   8 מס' דרכון passport number
 *   9..39       days 1..31 of the month, each holding a {@link Worker.WorkerState} symbol
 * </pre>
 */
public final class WorkerParser {
    private static final int LOCATION_COLUMN = 0;
    private static final int PROFESSION_COLUMN = 2;
    private static final int SITE_OR_TENURE_COLUMN = 3;
    private static final int COMPANY_COLUMN = 4;
    private static final int ID_COLUMN = 5;
    private static final int COUNTRY_COLUMN = 6;
    private static final int NAME_COLUMN = 7;
    private static final int PASSPORT_COLUMN = 8;
    private static final int FIRST_DAY_COLUMN = 9; // day 1 of the month; day d is at FIRST_DAY_COLUMN - 1 + d

    // Header texts used to recognize the sheet.
    private static final String ID_HEADER = "עובד";
    private static final String NAME_HEADER = "שם עובד";

    private WorkerParser() {
    }

    /**
     * Returns every worker in the sheet once, whatever their state today.
     * A worker can appear on several rows (one per site/company); the row with their most recent
     * entry up to today decides their company, location, profession, tenure and state.
     */
    public static List<Worker> parseWorkerCsv(Path csvFile) throws IOException, UserFacingException {
        List<String> lines = readLines(csvFile);
        checkHeader(csvFile, lines);
        int today = LocalDate.now().getDayOfMonth();

        // Group the rows by worker ID (skipping the header row). Rows without an ID are not workers:
        // the weekday row under the header, the totals row at the bottom and empty rows.
        Map<String, List<List<String>>> rowsById = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> fields = splitCsvLine(lines.get(i));
            String id = cell(fields, ID_COLUMN);
            if (id.isEmpty()) continue;
            rowsById.computeIfAbsent(id, k -> new ArrayList<>()).add(fields);
        }

        List<Worker> workers = new ArrayList<>();
        for (List<List<String>> rows : rowsById.values()) {
            List<String> row = mostRecentRow(rows, today);
            String company = cell(row, COMPANY_COLUMN);
            String tenure = company.equals(Worker.UNASSIGNED_COMPANY) ? cell(row, SITE_OR_TENURE_COLUMN) : "";
            workers.add(new Worker(
                    cell(row, LOCATION_COLUMN), cell(row, PROFESSION_COLUMN), company, cell(row, ID_COLUMN),
                    cell(row, COUNTRY_COLUMN), cell(row, NAME_COLUMN), cell(row, PASSPORT_COLUMN),
                    Worker.WorkerState.translateSymbolToState(cell(row, dayColumn(today))), tenure));
        }
        if (workers.isEmpty()) {
            throw new UserFacingException(csvFile + " has the sheet's header but no workers (no row has a worker ID in"
                    + " column " + columnLetter(ID_COLUMN) + "). Check that the right sheet was exported.");
        }
        return workers;
    }

    private static List<String> readLines(Path csvFile) throws IOException, UserFacingException {
        try {
            return new FileIOHandler().readAllLines(csvFile);
        } catch (MalformedInputException e) {
            throw new UserFacingException(csvFile + " is not saved as UTF-8, so its Hebrew text cannot be read."
                    + " In Excel, save the sheet again with File > Save As > \"CSV UTF-8 (Comma delimited)\""
                    + " (not the plain \"CSV (Comma delimited)\").", e);
        } catch (FileSystemException e) {
            throw new UserFacingException("Cannot read " + csvFile + ": " + e.getMessage()
                    + ". If it is open in another program, close it and run again.", e);
        }
    }

    // Makes sure this is the attendance sheet: the worker ID and name columns must be where the parser expects them.
    private static void checkHeader(Path csvFile, List<String> lines) throws UserFacingException {
        if (lines.isEmpty()) {
            throw new UserFacingException(csvFile + " is empty. Export the month's sheet again.");
        }
        // Excel starts a UTF-8 CSV with an invisible byte order mark; it is not part of the first header.
        List<String> header = splitCsvLine(lines.getFirst().replace("﻿", ""));
        boolean idOk = cell(header, ID_COLUMN).equals(ID_HEADER);
        boolean nameOk = cell(header, NAME_COLUMN).equals(NAME_HEADER);
        if (!idOk || !nameOk) {
            throw new UserFacingException(csvFile + " does not look like the attendance sheet: column "
                    + columnLetter(ID_COLUMN) + " should be \"" + ID_HEADER + "\" (found \"" + cell(header, ID_COLUMN)
                    + "\") and column " + columnLetter(NAME_COLUMN) + " should be \"" + NAME_HEADER + "\" (found \""
                    + cell(header, NAME_COLUMN) + "\"). Check that the month's sheet was exported, with the header"
                    + " in the first row. If the sheet's columns have changed, update the column constants in"
                    + " WorkerParser.");
        }
    }

    // Excel's letter for a 0-based column index below 26 (0 = A).
    private static char columnLetter(int column) {
        return (char) ('A' + column);
    }

    private static int dayColumn(int day) {
        return FIRST_DAY_COLUMN - 1 + day;
    }

    // The row with the latest filled-in day up to today; if none has any entry, the first row.
    private static List<String> mostRecentRow(List<List<String>> rows, int today) {
        List<String> best = rows.getFirst();
        int bestDay = 0;
        for (List<String> row : rows) {
            int lastDay = lastFilledDay(row, today);
            if (lastDay > bestDay) {
                best = row;
                bestDay = lastDay;
            }
        }
        return best;
    }

    // The latest day (1..today) that has an entry in this row, or 0 if none.
    private static int lastFilledDay(List<String> row, int today) {
        for (int day = today; day >= 1; day--) {
            if (!cell(row, dayColumn(day)).isEmpty()) {
                return day;
            }
        }
        return 0;
    }

    // The trimmed field at the given index, or "" if the row is shorter than that.
    private static String cell(List<String> row, int index) {
        return index < row.size() ? row.get(index).trim() : "";
    }

    // Splits a CSV line on commas, except commas inside double quotes ("ירוחם, צבי בורנשטיין 1").
    // A doubled quote inside a quoted field ("סה""כ") is read as a single quote character.
    private static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
