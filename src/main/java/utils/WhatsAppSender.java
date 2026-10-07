package utils;

import java.io.IOException;
import java.io.Reader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends a file to one WhatsApp number through Meta's WhatsApp Cloud API.
 * Sending a file takes two steps: upload it to Meta to get a media ID, then send a message that points to that ID.
 *
 * Settings are read from a properties file (keep it out of version control, it holds the access token):
 *   token          = access token (a permanent System User token from Meta Business settings)
 *   phoneNumberId  = the ID of the sending number (WhatsApp > API Setup in the Meta app dashboard), not the number itself
 *   recipient      = recipient's number in international format, digits only, e.g. 972501234567
 *   template       = (optional) name of an approved template with a DOCUMENT header
 *   templateLanguage = (optional) the template's language code, default "he"
 *
 * Without a template, WhatsApp only delivers the message if the recipient has messaged the sending
 * number in the last 24 hours. A template message is delivered at any time.
 *
 * Every failure is reported as a {@link UserFacingException} that explains Meta's error and how to fix it.
 */
public final class WhatsAppSender {
    private static final String GRAPH_API = "https://graph.facebook.com/v23.0/";
    private static final String PDF_MIME = "application/pdf";
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    // Fields of Meta's error response: {"error":{"message":"...","code":190,"error_data":{"details":"..."}}}
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*(\\d+)");
    private static final Pattern ERROR_DETAILS = Pattern.compile("\"details\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final String token;
    private final String phoneNumberId;
    private final String recipient;
    private final String template;
    private final String templateLanguage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public WhatsAppSender(String token, String phoneNumberId, String recipient, String template, String templateLanguage) {
        this.token = token;
        this.phoneNumberId = phoneNumberId;
        this.recipient = recipient;
        this.template = template;
        this.templateLanguage = templateLanguage;
    }

    /** Creates a sender from a properties file (see the class comment for the keys). */
    public static WhatsAppSender fromProperties(Path path) throws IOException, UserFacingException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (CharacterCodingException e) {
            throw new UserFacingException(path + " is not saved as UTF-8. Save it again as UTF-8 (in Notepad:"
                    + " File > Save As > Encoding: UTF-8).", e);
        }
        String phoneNumberId = required(props, "phoneNumberId", path);
        if (!DIGITS.matcher(phoneNumberId).matches()) {
            throw new UserFacingException("\"phoneNumberId\" in " + path + " should be digits only, got \""
                    + phoneNumberId + "\". It is the Phone number ID from WhatsApp > API Setup in the Meta app"
                    + " dashboard, not the phone number itself.");
        }
        String recipient = required(props, "recipient", path);
        if (!DIGITS.matcher(recipient).matches()) {
            throw new UserFacingException("\"recipient\" in " + path + " should be digits only in international"
                    + " format, e.g. 972501234567 (no +, spaces, dashes or leading 0), got \"" + recipient + "\".");
        }
        String template = props.getProperty("template", "").trim();
        return new WhatsAppSender(
                required(props, "token", path),
                phoneNumberId,
                recipient,
                template.isEmpty() ? null : template,
                props.getProperty("templateLanguage", "he").trim());
    }

    /**
     * Uploads the document (.pdf or .docx) and sends it to the recipient; the file name shown in WhatsApp is the
     * file's own name. Prints what was sent.
     */
    public void sendDocument(Path file) throws IOException, InterruptedException, UserFacingException {
        String mediaId = uploadMedia(file, mimeType(file));
        String document = "{\"id\":\"" + mediaId + "\",\"filename\":\"" + escapeJson(file.getFileName().toString()) + "\"}";

        String message;
        if (template != null) {
            message = "{\"messaging_product\":\"whatsapp\",\"to\":\"" + recipient + "\",\"type\":\"template\","
                    + "\"template\":{\"name\":\"" + escapeJson(template) + "\","
                    + "\"language\":{\"code\":\"" + escapeJson(templateLanguage) + "\"},"
                    + "\"components\":[{\"type\":\"header\",\"parameters\":[{\"type\":\"document\",\"document\":" + document + "}]}]}}";
        } else {
            message = "{\"messaging_product\":\"whatsapp\",\"to\":\"" + recipient + "\",\"type\":\"document\","
                    + "\"document\":" + document + "}";
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(GRAPH_API + phoneNumberId + "/messages"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8))
                .build();
        String body = send(request, "send the message");

        Matcher m = ID_PATTERN.matcher(body);
        System.out.println("WhatsApp accepted the message to " + recipient + (m.find() ? " (id " + m.group(1) + ")" : ""));
        if (template == null) {
            // Meta accepts the message either way; it only fails to deliver it later, and doesn't tell us.
            System.out.println("Note: no template is set, so it only arrives if " + recipient + " sent a message to"
                    + " the business number in the last 24 hours. Set \"template\" in whatsapp.properties to"
                    + " deliver at any time.");
        }
    }

    private static String mimeType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return PDF_MIME;
        }
        if (name.endsWith(".docx")) {
            return DOCX_MIME;
        }
        throw new IllegalArgumentException("Can only send .pdf or .docx files, not " + file.getFileName());
    }

    // Uploads the file and returns its media ID.
    private String uploadMedia(Path file, String mimeType) throws IOException, InterruptedException, UserFacingException {
        String boundary = "----WorkerDocMaker" + UUID.randomUUID();
        List<byte[]> parts = new ArrayList<>();
        parts.add(formField(boundary, "messaging_product", "whatsapp"));
        parts.add(formField(boundary, "type", mimeType));
        // The upload name is only used by Meta internally; an ASCII name avoids encoding issues with Hebrew.
        String uploadName = mimeType.equals(PDF_MIME) ? "report.pdf" : "report.docx";
        parts.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + uploadName + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(Files.readAllBytes(file));
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(GRAPH_API + phoneNumberId + "/media"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                .build();
        String body = send(request, "upload the report");

        Matcher m = ID_PATTERN.matcher(body);
        if (!m.find()) {
            throw new UserFacingException("WhatsApp accepted the upload but returned no media ID: " + body);
        }
        return m.group(1);
    }

    // Sends the request and returns the response body; a failure becomes an explanation of what to fix.
    private String send(HttpRequest request, String action) throws IOException, InterruptedException, UserFacingException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new UserFacingException("WhatsApp did not answer in time while trying to " + action
                    + ". Check the internet connection and run again.", e);
        } catch (ConnectException e) {
            throw new UserFacingException("Could not connect to WhatsApp (graph.facebook.com) to " + action
                    + ". Check the internet connection, and that no firewall or proxy blocks Java.", e);
        } catch (IOException e) {
            throw new UserFacingException("The connection to WhatsApp failed while trying to " + action + ": "
                    + e + ". Check the internet connection and run again.", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new UserFacingException(explainError(action, response.statusCode(), response.body()));
        }
        return response.body();
    }

    // Turns Meta's error response into a message that says what went wrong and what to do.
    private String explainError(String action, int status, String body) {
        String message = firstGroup(ERROR_MESSAGE, body);
        String details = firstGroup(ERROR_DETAILS, body);
        String code = firstGroup(ERROR_CODE, body);

        StringBuilder sb = new StringBuilder("WhatsApp refused to ").append(action).append(" (HTTP ").append(status);
        if (code != null) {
            sb.append(", error ").append(code);
        }
        sb.append("): ").append(message != null ? message : body);
        if (details != null && !details.equals(message)) {
            sb.append(" - ").append(details);
        }
        String hint = code == null ? null : hint(code);
        if (hint != null) {
            sb.append("\n").append(hint);
        }
        return sb.toString();
    }

    // What to do about the most common Cloud API errors (https://developers.facebook.com/docs/whatsapp/cloud-api/support/error-codes).
    private String hint(String code) {
        return switch (code) {
            case "190" -> "The access token in whatsapp.properties is wrong or has expired. Temporary tokens from"
                    + " API Setup last about 24 hours; create a permanent System User token (see the README).";
            case "10", "200", "3" -> "The token is missing a permission. Generate it with the"
                    + " whatsapp_business_messaging (and whatsapp_business_management) permissions.";
            case "100", "33" -> "Meta did not accept a value. Check that phoneNumberId (" + phoneNumberId + ") is the"
                    + " Phone number ID from API Setup, and that the template name and language are right.";
            case "131030" -> "The recipient " + recipient + " is not on the test number's allowed list. Add and"
                    + " verify it under WhatsApp > API Setup > To, or send from a real business number.";
            case "132000", "132001" -> "The template \"" + template + "\" in language \"" + templateLanguage
                    + "\" does not exist or is not approved yet. Check it in WhatsApp Manager > Message templates.";
            case "132012" -> "The template \"" + template + "\" needs a Document header to carry the report.";
            case "133010" -> "The sending number is not registered with the Cloud API yet.";
            case "131031" -> "The WhatsApp business account is locked or restricted. Check Meta Business Suite.";
            case "4", "80007", "130429", "131048", "131056" -> "Too many messages for now. Wait a while and run again.";
            case "131026" -> "The recipient " + recipient + " cannot receive this message (not a WhatsApp number,"
                    + " or an outdated WhatsApp).";
            case "131047" -> "More than 24 hours passed since the recipient last wrote to the business number."
                    + " Use an approved template (\"template\" in whatsapp.properties).";
            default -> null;
        };
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? unescapeJson(m.group(1)) : null;
    }

    // Enough JSON unescaping for Meta's error messages: \" \\ \/ \n and \\uXXXX.
    private static String unescapeJson(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                sb.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n' -> sb.append(' ');
                case 'u' -> {
                    String hex = i + 4 < text.length() ? text.substring(i + 1, i + 5) : "";
                    if (hex.matches("[0-9a-fA-F]{4}")) {
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    } else {
                        sb.append("\\u");
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }

    private static byte[] formField(String boundary, String name, String value) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String required(Properties props, String key, Path path) throws UserFacingException {
        String value = props.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new UserFacingException("\"" + key + "\" is missing or empty in " + path.toAbsolutePath()
                    + ". See whatsapp.properties.example for what goes there.");
        }
        return value;
    }

    private static String escapeJson(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
