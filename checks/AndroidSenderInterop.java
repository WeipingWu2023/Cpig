import app.copy.data.transfer.LocalSendConnection;
import app.copy.domain.LanDevice;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Runs the APK's actual HTTPS connection class against the Windows receiver, without Android UI. */
public final class AndroidSenderInterop {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path folder = Path.of(args[1]);
        String pin = args[2];
        var bootstrap = new LocalSendConnection(new LanDevice("", "Receiver", "127.0.0.1", port, "Windows"), true);
        String info = post(bootstrap, "register", "", "{}".getBytes(StandardCharsets.UTF_8));
        String fingerprint = bootstrap.getObservedFingerprint();
        check(fingerprint != null && info.contains(fingerprint), "register identity matches the TLS certificate");
        var peer = new LanDevice(fingerprint, "Receiver", "127.0.0.1", port, "Windows");
        var pinned = new LocalSendConnection(peer, false);
        post(pinned, "register", "", "{}".getBytes(StandardCharsets.UTF_8));
        check(fingerprint.equals(pinned.getObservedFingerprint()), "discovered certificate pin accepted");
        try {
            var wrong = new LocalSendConnection(new LanDevice("0".repeat(64), "Receiver", "127.0.0.1", port, "Windows"), false);
            post(wrong, "register", "", "{}".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("Wrong certificate pin was accepted");
        } catch (SSLException expected) { System.out.println("PASS wrong certificate pin rejected"); }

        byte[] payload = new byte[2 * 1024 * 1024 + 17];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31 + 7);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        String metadata = "{\"info\":{\"alias\":\"Android JVM interoperability test\",\"version\":\"2.2\"},"
            + "\"files\":{\"jvm-file\":{\"id\":\"jvm-file\",\"fileName\":\"android-interop.mp4\",\"size\":"
            + payload.length + ",\"fileType\":\"video/mp4\",\"sha256\":\"" + hash + "\"}}}";
        try {
            post(pinned, "prepare-upload", "?pin=000000", metadata.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("Wrong PIN was accepted");
        } catch (java.io.IOException expected) { check(expected.getMessage().contains("PIN"), "wrong PIN becomes the Android-readable error"); }
        String prepared = post(pinned, "prepare-upload", "?pin=" + pin, metadata.getBytes(StandardCharsets.UTF_8));
        String session = value(prepared, "sessionId");
        String token = value(prepared, "jvm-file");
        post(pinned, "upload", "?sessionId=" + session + "&fileId=jvm-file&token=" + token, payload);
        check(Arrays.equals(payload, Files.readAllBytes(folder.resolve("android-interop.mp4"))), "actual Android HTTPS class uploaded exact bytes with SHA-256");
        var image = new java.awt.image.BufferedImage(32, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 32; x++) image.setRGB(x, y, 0xffaa315f + x * 256 + y);
        var pngOutput = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", pngOutput);
        byte[] png = pngOutput.toByteArray();
        String pngHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
        String pngMetadata = "{\"info\":{\"alias\":\"Android PNG test\",\"version\":\"2.2\"},"
            + "\"files\":{\"png-file\":{\"id\":\"png-file\",\"fileName\":\"screenshot-interop.png\",\"size\":"
            + png.length + ",\"fileType\":\"image/png\",\"sha256\":\"" + pngHash + "\"}}}";
        String pngPrepared = post(pinned, "prepare-upload", "?pin=" + pin, pngMetadata.getBytes(StandardCharsets.UTF_8));
        post(pinned, "upload", "?sessionId=" + value(pngPrepared, "sessionId")
            + "&fileId=png-file&token=" + value(pngPrepared, "png-file"), png);
        Path receivedPng = folder.resolve("screenshot-interop.png");
        check(Arrays.equals(png, Files.readAllBytes(receivedPng))
            && javax.imageio.ImageIO.read(receivedPng.toFile()).getRGB(31, 15) == image.getRGB(31, 15),
            "PNG screenshot transferred with exact bytes and readable pixels");
        System.out.println("Android/Windows protocol interoperability checks passed.");
    }

    private static String post(LocalSendConnection client, String path, String query, byte[] payload) throws Exception {
        HttpsURLConnection connection = client.open(path, query);
        try {
            connection.setRequestProperty("Content-Type", path.equals("upload") ? "application/octet-stream" : "application/json");
            connection.setFixedLengthStreamingMode(payload.length);
            try (var output = connection.getOutputStream()) {
                for (int offset = 0; offset < payload.length; offset += 65536)
                    output.write(payload, offset, Math.min(65536, payload.length - offset));
            }
            return LocalSendConnection.Companion.response(connection);
        } finally { connection.disconnect(); }
    }
    private static String value(String json, String key) {
        var match = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!match.find()) throw new AssertionError("Missing " + key);
        return match.group(1);
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS " + description);
    }
}
