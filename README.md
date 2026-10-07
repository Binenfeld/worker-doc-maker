# WorkerDocMaker

Turns the monthly worker attendance sheet (Excel) into a Hebrew Word report, converts it to PDF and sends the PDF
on WhatsApp.

```
1.csv (exported from Excel) ──► WorkerParser ──► List<Worker> ──► ReportFactory ──► פירוט עובדים <date>.docx ──► PdfConverter ──► .pdf ──► WhatsAppSender
```

## What the report contains

The report is right-to-left Hebrew, with colored tables. Each section starts on its own page, and its tables
are half the page wide, two side by side (the first on the right). Headings that show a number of workers read
`<title> - סך הכל: <amount>`.

| Page | Section | Content |
|---|---|---|
| 1 | Title + date line | `פירוט עובדים`, today's date and the total number of workers |
| 1 | חברות | Every company (except לא משוייך), alphabetical, with its number of workers. The list is split over the two halves, and the left half ends with the total row |
| 2 | לא משוייך | Unassigned workers, one table per country of origin, listing each worker's name, profession and tenure (ותק) |
| 3 | סיכום | Number of workers per country of origin (right) and number of workers on intervisa today (left) |

## Requirements

- JDK 21 or newer (the project is developed with JDK 26)
- Gradle, through the included wrapper (`gradlew`); no external libraries are needed
- Excel, to export the sheet to CSV
- Microsoft Word (desktop), to convert the report to PDF. `PdfConverter` drives it through PowerShell, so this
  needs Windows. Without Word the run fails after the .docx is written.

## Running it

1. **Export the sheet.** Open the attendance workbook, select the current month's sheet (e.g. `10.2026`) and use
   File → Save As → **CSV UTF-8 (Comma delimited)**. Save it in `C:\workerdocmaker` under any name ending in
   `.csv`. It must be the UTF-8 variant, or the Hebrew text will be garbled.
2. **WhatsApp settings** (once): copy `whatsapp.properties.example` to `C:\workerdocmaker\whatsapp.properties`
   and fill it in (see [WhatsApp](#whatsapp)). It holds the access token, so it is not in the repository: a
   fresh clone has none, and the run stops and says so until the file is there.
3. **Build** (once, and again after code changes):
   ```
   powershell -ExecutionPolicy Bypass -File scripts\build.ps1
   ```
   This produces `build\libs\WorkerDocMaker.jar`. The script uses `JAVA_HOME` if it is set, otherwise the newest
   JDK in `~\.jdks` (where IntelliJ downloads JDKs).
4. **Run:**
   ```
   powershell -ExecutionPolicy Bypass -File scripts\run.ps1
   ```
   Or just **double-click `scripts\run.cmd`**, which does the same and keeps the window open to show the result.
   You can also run `Main` from IntelliJ. Options:
   - `-InputFolder D:\some\folder`: use a different folder
   - `-NoWhatsApp`: make the report and the PDF without sending anything, for testing
   - `-Debug`: also print every worker read, and the full stack trace of an error
5. The program reads the **newest** `.csv` in the folder and writes the report next to it as
   `פירוט עובדים <yyyy-mm-dd>.docx`, plus a PDF copy (`.pdf`, same name) made with Word. Then it sends the PDF
   on WhatsApp.

The default input folder is `Main.DEFAULT_INPUT_FOLDER`. The jar takes the same options directly:
`java -jar WorkerDocMaker.jar [folder] [--no-whatsapp] [--debug]`. Excel lock files (`~$…`) are ignored.
`-ExecutionPolicy Bypass` is only needed if Windows blocks running scripts.

### When something goes wrong

The run stops with exit code 1 and a line starting with `ERROR:` that says what is wrong and how to fix it. For
example, it reports a missing folder or CSV, a CSV saved as plain CSV instead of CSV UTF-8, a file that isn't
the attendance sheet, a report left open in Word, a missing Word install, missing or malformed WhatsApp
settings, and Meta's errors (expired token, number not on the test number's allowed list, unapproved template,
no internet connection). `run.cmd` then shows "Something went wrong". An `ERROR: Unexpected error` comes with a
stack trace; that one is a bug or something unusual on the computer.

## Input format

`WorkerParser` expects the sheet's column layout (0-based):

| Column | Header | Meaning |
|---|---|---|
| 0 | כתובת | Address / location |
| 1 | הסעות | Transport (not used) |
| 2 | מקצוע | Profession |
| 3 | אתר | Site for assigned workers; **tenure** (`חודש`, `חצי שנה`, `שנתיים`…) for unassigned workers |
| 4 | שם חברה | Company (`לא משוייך` = unassigned) |
| 5 | עובד | Andromeda worker ID |
| 6 | מוצא | Country of origin |
| 7 | שם עובד | Name |
| 8 | מס' דרכון | Passport number |
| 9–39 | 1…31 | One column per day of the month |

Each day cell holds a state symbol:

| Symbol | State |
|---|---|
| `1` | Working |
| `א` | Intervisa |
| `ח` | Sick |
| `ס` | Refusing to work |
| empty | Not working |

How rows are read:

- **The first row is the header** and is skipped.
- **Rows without a worker ID are ignored.** That covers the weekday row, the totals row and empty rows.
- **A worker can appear on several rows** (one per company/site). The row with the most recent filled-in day,
  up to today, decides their company and details.
- **State comes from today's column**, based on the computer's date.

If the sheet's columns change, update the column constants at the top of `WorkerParser`.

## WhatsApp

`WhatsAppSender` uses Meta's [WhatsApp Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api).
It uploads the PDF, then sends it to one recipient as a document message. It is configured through
`whatsapp.properties`, looked up first in the input folder (`C:\workerdocmaker`), then in the folder the program
runs from (the project folder for `run.ps1` and IntelliJ). Copy `whatsapp.properties.example` and fill in:

| Key | Value |
|---|---|
| `token` | Access token. Use a permanent System User token (Meta Business settings → System users) with the `whatsapp_business_messaging` permission |
| `phoneNumberId` | The sending number's **Phone number ID** (WhatsApp → API Setup in the Meta app dashboard) |
| `recipient` | Recipient number, international format, digits only (e.g. `972501234567`) |
| `template` | Optional: name of an approved template with a Document header |
| `templateLanguage` | The template's language code (default `he`) |

Things to know:

- **`whatsapp.properties` holds a secret** and is git-ignored. Never commit it.
- **Without a template**, WhatsApp only delivers if the recipient messaged the sending number in the last
  24 hours. A template message is delivered at any time.
- **Meta's test number** can only send to numbers verified on the API Setup page. Otherwise it fails with
  error `131030 Recipient phone number not in allowed list`.
- **An accepted message isn't always delivered.** A successful run means Meta accepted the message; delivery
  failures, such as an expired 24-hour window, are only reported by Meta afterwards. Without a template, the
  run prints a reminder of this.
- **Without the file**, the run stops before doing any work and says where it looked. Use `-NoWhatsApp` to
  make the report without sending it.

## Project structure

```
scripts/
├── build.ps1                 builds build\libs\WorkerDocMaker.jar
├── run.ps1                   runs the jar on the newest CSV in C:\workerdocmaker
└── run.cmd                   double-click launcher for run.ps1
src/main/java/
├── Main.java                 entry point: newest CSV → parse → write report → PDF → send
├── models/
│   └── Worker.java           one worker + WorkerState (the day-symbol enum)
└── utils/
    ├── WorkerParser.java     reads the CSV into workers
    ├── ReportFactory.java    lays out the report (sections, tables, sorting)
    ├── DocxWriter.java       minimal .docx writer (titles, headings, RTL, colored tables) without libraries
    ├── PdfConverter.java     .docx → PDF through Word (PowerShell + COM); always closes the Word it starts
    ├── WhatsAppSender.java   WhatsApp Cloud API client (upload + send), explains Meta's errors
    ├── UserFacingException.java  a problem the user can fix; Main prints its message without a stack trace
    └── FileIOHandler.java    small UTF-8 file helpers
```

`DocxWriter` writes the Office Open XML parts by hand and zips them, so the project has no dependencies.
Headings use Word's built-in Heading styles, so they appear in Word's navigation pane.

## License

Copyright (C) 2026 Binenfeld

This program is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see the license for details.
