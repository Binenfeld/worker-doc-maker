package utils;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 */
public final class WhatsAppSender {
    private static final String GRAPH_API = "https://graph.facebook.com/v23.0/";
    private static final String PDF_MIME = "application/pdf";
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private final String token;
    private final String phoneNumberId;
    private final String recipient;
    private final String template;
    private final String templateLanguage;
    private final HttpClient http = HttpClient.newHttpClient();

    public WhatsAppSender(String token, String phoneNumberId, String recipient, String template, String templateLanguage) {
        this.token = token;
        this.phoneNumberId = phoneNumberId;
        this.recipient = recipient;
        this.template = template;
        this.templateLanguage = templateLanguage;
    }

    /** Creates a sender from a properties file (see the class comment for the keys). */
    public static WhatsAppSender fromProperties(Path path) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        String template = props.getProperty("template", "").trim();
        return new WhatsAppSender(
                required(props, "token", path),
                required(props, "phoneNumberId", path),
                required(props, "recipient", path),
                template.isEmpty() ? null : template,
                props.getProperty("templateLanguage", "he").trim());
    }

    /**
     * Uploads the document (.pdf or .docx) and sends it to the recipient; the file name shown in WhatsApp is the
     * file's own name.
     */
    public void sendDocument(Path file) throws IOException, InterruptedException {
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
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8))
                .build();
        send(request, "send message");
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
    private String uploadMedia(Path file, String mimeType) throws IOException, InterruptedException {
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
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                .build();
        String body = send(request, "upload file");

        Matcher m = ID_PATTERN.matcher(body);
        if (!m.find()) {
            throw new IOException("WhatsApp upload returned no media ID: " + body);
        }
        return m.group(1);
    }

    // Sends the request and returns the response body; throws with Meta's error message if it failed.
    private String send(HttpRequest request, String action) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("WhatsApp failed to " + action + " (HTTP " + response.statusCode() + "): " + response.body());
        }
        return response.body();
    }

    private static byte[] formField(String boundary, String name, String value) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String required(Properties props, String key, Path path) {
        String value = props.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing \"" + key + "\" in " + path.toAbsolutePath());
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
