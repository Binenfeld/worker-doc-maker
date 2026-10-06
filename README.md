# WorkerDocMaker

Turns the monthly worker attendance sheet (Excel) into a Hebrew Word report and sends it on WhatsApp.

```
1.csv (exported from Excel) ──► WorkerParser ──► List<Worker> ──► ReportFactory ──► פירוט עובדים <date>.docx ──► WhatsAppSender
```

## What the report contains

The report is right-to-left Hebrew, with colored tables:

| Section | Content |
|---|---|
| Title + date line | `פירוט עובדים`, today's date and the total number of workers |
| Companies table | Every company (except לא משוייך), alphabetical, with its number of workers and a total row |
| לא משוייך | Unassigned workers, one sub-heading per country of origin, listing each worker's name, profession and tenure (ותק) |
| סיכום → עובדים לפי ארץ מוצא | Number of workers per country of origin |
| סיכום → אינטרויזה | Number of workers on intervisa today |

## Requirements

- JDK 21 or newer (the project is developed with JDK 26)
- Gradle, through the included wrapper (`gradlew`); no external libraries are needed
- Excel, to export the sheet to CSV

## Running it

1. **Export the sheet.** Open the attendance workbook, select the current month's sheet (e.g. `10.2026`) and use
   File → Save As → **CSV UTF-8 (Comma delimited)**. Save it as `1.csv` in the data folder.
   It must be the UTF-8 variant, or the Hebrew text will be garbled.
2. **Run `Main`** from IntelliJ, or from the command line:
   ```
   gradlew compileJava
   java -cp build/classes/java/main Main
   ```
3. The report is written to the data folder as `פירוט עובדים <yyyy-mm-dd>.docx`. If `whatsapp.properties`
   exists, the report is also sent on WhatsApp.

The data folder is set in `Main.DATA_FOLDER` (currently the `excel docs` folder on the owner's desktop).
Change it there if the files live somewhere else.

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
It uploads the .docx, then sends it to one recipient as a document message. It is configured through
`whatsapp.properties` in the project folder. Copy `whatsapp.properties.example` and fill in:

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
  failures, such as an expired 24-hour window, are only reported by Meta afterwards.
- **Without the file**, the WhatsApp step is skipped and the report is still written.

## Project structure

```
src/main/java/
├── Main.java                 entry point: parse → write report → send
├── models/
│   └── Worker.java           one worker + WorkerState (the day-symbol enum)
└── utils/
    ├── WorkerParser.java     reads the CSV into workers
    ├── ReportFactory.java    lays out the report (sections, tables, sorting)
    ├── DocxWriter.java       minimal .docx writer (titles, headings, RTL, colored tables) without libraries
    ├── WhatsAppSender.java   WhatsApp Cloud API client (upload + send)
    └── FileIOHandler.java    small UTF-8 file helpers
```

`DocxWriter` writes the Office Open XML parts by hand and zips them, so the project has no dependencies.
Headings use Word's built-in Heading styles, so they appear in Word's navigation pane.

## License

Proprietary, all rights reserved. See [LICENSE](LICENSE). Copying, modifying or distributing this code is not
permitted without written permission.
