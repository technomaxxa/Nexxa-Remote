import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.sound.sampled.*;
import javax.net.ssl.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.geom.Path2D;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Nexxa Remote 0.7.5 - Windows-first private remote desktop with high-detail streaming.
 * Runtime: Java 25 recommended (Java 21+ bytecode compatibility). No third-party Java libraries are required.
 *
 * Network model: the two PCs are connected by Tailscale. Nexxa binds its TLS listener
 * to the host's Tailscale IPv4 address; no public Nexxa relay or router port-forwarding
 * is needed. For different Tailscale accounts, share the host device with the viewer
 * account and have the recipient accept it.
 */
public final class NexxaRemote {
    private static final String APP_NAME = "Nexxa Remote";
    private static final String APP_VERSION = "0.7.5";
    private static final int DEFAULT_PORT = 24800;
    private static final int MAGIC = 0x4E585233; // NXR3
    private static final int MSG_FRAME = 1;
    private static final int MSG_MOUSE_MOVE = 2;
    private static final int MSG_MOUSE_PRESS = 3;
    private static final int MSG_MOUSE_RELEASE = 4;
    private static final int MSG_KEY_PRESS = 5;
    private static final int MSG_KEY_RELEASE = 6;
    private static final int MSG_MOUSE_WHEEL = 7;
    private static final int MSG_HOST_CLIPBOARD = 8;
    private static final int MSG_AUDIO = 9;
    private static final int MSG_VIEWER_CLIPBOARD = 10;
    private static final int MSG_CONTROL_STATUS = 11;
    private static final int MSG_REMOTE_CURSOR = 12;
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024; // Supports high-detail 1080p/1440p JPEG frames.
    private static final int MAX_CLIPBOARD_BYTES = 256 * 1024;
    private static final int MAX_AUDIO_BYTES = 16 * 1024;
    private static final String TAILSCALE_ADMIN_MACHINES = "https://login.tailscale.com/admin/machines";

    private static final Color BG = new Color(15, 20, 31);
    private static final Color PANEL = new Color(24, 32, 47);
    private static final Color PANEL_ALT = new Color(31, 42, 60);
    private static final Color TEXT = new Color(236, 242, 250);
    private static final Color MUTED = new Color(151, 166, 187);
    private static final Color ACCENT = new Color(78, 154, 255);
    private static final Color GREEN = new Color(67, 190, 132);

    /** True only after checking the actual Windows process token; never inferred from a command-line flag. */
    private static volatile boolean runningAsAdministrator;
    /** Preserves an already host-approved remote-control preference only across the explicit UAC restart. */
    private static volatile boolean restoreRemoteControlPermission;

    private NexxaRemote() { }

    public static void main(String[] args) {
        if (args.length > 0 && "--self-test".equalsIgnoreCase(args[0])) {
            try {
                runSelfTests();
                System.out.println("Nexxa Remote self-test: PASS");
            } catch (Exception e) {
                System.err.println("Nexxa Remote self-test: FAIL - " + e.getMessage());
                e.printStackTrace(System.err);
                System.exit(1);
            }
            return;
        }
        String invitationArgument = Arrays.stream(args)
                .filter(value -> value != null && value.regionMatches(true, 0, "nexxa://", 0, 8))
                .findFirst().orElse("");
        boolean inheritedServiceOwnership = hasArgument(args, "--tailscale-service-owned");
        boolean inheritedConnectionOwnership = hasArgument(args, "--tailscale-connection-owned");
        restoreRemoteControlPermission = hasArgument(args, "--restore-remote-control-permission");
        runningAsAdministrator = isWindows() && isCurrentProcessAdministrator();
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            installModernTheme();
            MainWindow window = new MainWindow();
            window.inheritTailscaleOwnership(inheritedServiceOwnership, inheritedConnectionOwnership);
            window.setVisible(true);
            window.hostPanel.resetScroll();
            if (!invitationArgument.isBlank()) window.openInvitation(invitationArgument);
            window.startTailscale();
        });
    }

    private static boolean hasArgument(String[] args, String value) {
        return Arrays.stream(args).anyMatch(arg -> value.equalsIgnoreCase(arg));
    }

    /** Uses the current process token, not the presence of an argument or the account's group membership alone. */
    private static boolean isCurrentProcessAdministrator() {
        if (!isWindows()) return false;
        String script = "$p=New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent()); " +
                "if ($p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { 'NEXXA_ADMIN=1' } else { 'NEXXA_ADMIN=0' }";
        TailscaleManager.CommandResult result = TailscaleManager.runCommand(
                List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script), 8_000);
        return result.exitCode() == 0 && result.output().contains("NEXXA_ADMIN=1");
    }

    /** Starts the same JAR with the normal Windows UAC consent/credential prompt. */
    private static String launchElevatedInstance(boolean tailscaleServiceOwned, boolean tailscaleConnectionOwned, boolean preserveRemoteControlPermission) {
        if (!isWindows()) return "Administrator mode is supported on Windows only.";
        if (runningAsAdministrator) return "Nexxa is already running as administrator.";
        try {
            Path javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
            Path javaw = javaHome.resolve("bin").resolve("javaw.exe");
            if (!Files.isRegularFile(javaw)) javaw = javaHome.resolve("bin").resolve("java.exe");
            if (!Files.isRegularFile(javaw)) return "Could not locate javaw.exe in the active private runtime. Start Nexxa using Start Nexxa Remote.bat.";

            String classPath = System.getProperty("java.class.path", "");
            int separator = classPath.indexOf(File.pathSeparatorChar);
            if (separator >= 0) classPath = classPath.substring(0, separator);
            if (classPath.isBlank()) return "Could not locate NexxaRemote.jar. Start Nexxa using Start Nexxa Remote.bat.";
            Path jar = Path.of(classPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar) || !jar.getFileName().toString().equalsIgnoreCase("NexxaRemote.jar")) {
                return "The running application is not a packaged NexxaRemote.jar. Use the included launcher before enabling administrator mode.";
            }
            Path workingDirectory = jar.getParent();
            String script = "$ErrorActionPreference='Stop'; try { " +
                    "$a='-Dfile.encoding=UTF-8 -jar \"' + $env:NEXXA_JAR + '\" --admin-elevated'; " +
                    "if ($env:NEXXA_TS_SERVICE -eq '1') { $a += ' --tailscale-service-owned' }; " +
                    "if ($env:NEXXA_TS_CONNECTION -eq '1') { $a += ' --tailscale-connection-owned' }; " +
                    "if ($env:NEXXA_REMOTE_CONTROL -eq '1') { $a += ' --restore-remote-control-permission' }; " +
                    "$child=Start-Process -FilePath $env:NEXXA_JAVA -ArgumentList $a -WorkingDirectory $env:NEXXA_DIR -Verb RunAs -PassThru; " +
                    "[Console]::Out.WriteLine('NEXXA_ELEVATED_STARTED:' + $child.Id); exit 0 " +
                    "} catch { [Console]::Error.WriteLine('UAC was cancelled or elevation failed: ' + $_.Exception.Message); exit 23 }";
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", script);
            builder.redirectErrorStream(true);
            builder.environment().put("NEXXA_JAVA", javaw.toString());
            builder.environment().put("NEXXA_JAR", jar.toString());
            builder.environment().put("NEXXA_DIR", workingDirectory.toString());
            builder.environment().put("NEXXA_TS_SERVICE", tailscaleServiceOwned ? "1" : "0");
            builder.environment().put("NEXXA_TS_CONNECTION", tailscaleConnectionOwned ? "1" : "0");
            builder.environment().put("NEXXA_REMOTE_CONTROL", preserveRemoteControlPermission ? "1" : "0");
            Process process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream stream = process.getInputStream()) { stream.transferTo(output); }
                catch (IOException ignored) { }
            }, "Nexxa-UAC-Output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return "The Windows UAC request timed out. No administrator restart was confirmed.";
            }
            reader.join(2_000);
            String result = output.toString(StandardCharsets.UTF_8).trim();
            if (process.exitValue() == 0 && result.contains("NEXXA_ELEVATED_STARTED:")) return "";
            return result.isBlank() ? "Windows did not start the elevated Nexxa process. The UAC request may have been cancelled." : result;
        } catch (Exception e) {
            return "Could not request Windows administrator elevation: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static void runSelfTests() throws Exception {
        Path temporary = Files.createTempDirectory("nexxa-self-test-");
        Path identityFile = temporary.resolve("identity.p12");
        char[] password = randomToken(32).toCharArray();
        try {
            X509Certificate certificate = createHostCertificate(identityFile.toFile(), password);
            String pin = fingerprint(certificate);
            if (normalizeFingerprint(pin).length() != 64) throw new AssertionError("SHA-256 fingerprint length is invalid");
            SSLContext serverContext = createServerContext(identityFile.toFile(), password);
            SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0);
            setModernTls(server);
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread accept = new Thread(() -> {
                try (SSLSocket peer = (SSLSocket) server.accept()) {
                    setModernTls(peer);
                    peer.startHandshake();
                    peer.getOutputStream().write(0x5A);
                    peer.getOutputStream().flush();
                } catch (Throwable e) { serverFailure.set(e); }
            }, "Nexxa-SelfTest-TLS");
            accept.setDaemon(true);
            accept.start();
            SSLContext clientContext = createPinnedClientContext(pin);
            try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory().createSocket("127.0.0.1", server.getLocalPort())) {
                setModernTls(client);
                client.setSoTimeout(5000);
                client.startHandshake();
                if (client.getInputStream().read() != 0x5A) throw new AssertionError("TLS test payload mismatch");
            } finally {
                server.close();
                accept.join(5000);
            }
            if (serverFailure.get() != null) throw new AssertionError("TLS server handshake failed", serverFailure.get());

            String accessKey = randomToken(32);
            String invitation = createInvite("100.64.0.10", 24800, pin, accessKey);
            Invite parsed = parseInvite(invitation);
            if (!parsed.host().equals("100.64.0.10") || parsed.port() != 24800 ||
                    !normalizeFingerprint(parsed.fingerprint()).equals(normalizeFingerprint(pin)) || !parsed.accessKey().equals(accessKey)) {
                throw new AssertionError("Invitation round-trip failed");
            }

            Rectangle fit = ScreenPanel.fitImageRectangle(1600, 800, 1920, 1080);
            if (fit == null || fit.x < 0 || fit.y < 0 || fit.x + fit.width > 1600 || fit.y + fit.height > 800) {
                throw new AssertionError("Viewer FIT rectangle must stay inside the available area");
            }
            double sourceAspect = 1920.0 / 1080.0;
            double fittedAspect = fit.width / (double) fit.height;
            if (Math.abs(sourceAspect - fittedAspect) > 0.005) {
                throw new AssertionError("Viewer FIT scaling must preserve the full remote-screen aspect ratio");
            }
            if (fit.width != 1422 || fit.height != 800) {
                throw new AssertionError("Viewer FIT sizing is not using the available workspace as expected");
            }

            BufferedImage testFrame = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = testFrame.createGraphics();
            try {
                g.setPaint(new GradientPaint(0, 0, new Color(15, 28, 60), 1920, 1080, new Color(110, 30, 130)));
                g.fillRect(0, 0, 1920, 1080);
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 42));
                g.drawString("Nexxa Remote high-detail test", 80, 120);
                g.setStroke(new BasicStroke(1.0f));
                for (int x = 0; x < 1920; x += 7) g.drawLine(x, 180, x, 920);
                for (int y = 180; y < 920; y += 7) g.drawLine(0, y, 1920, y);
                g.setColor(Color.WHITE); g.fillRect(100, 300, 420, 180);
                g.setColor(Color.BLACK); g.drawRect(100, 300, 420, 180);
            } finally { g.dispose(); }
            try (JpegEncoder encoder = new JpegEncoder()) {
                byte[] fastJpeg = encoder.encode(testFrame, StreamProfile.fast());
                if (fastJpeg.length == 0 || fastJpeg.length > MAX_FRAME_BYTES) throw new AssertionError("Fast JPEG frame size out of range");
                BufferedImage fastDecoded = ImageIO.read(new ByteArrayInputStream(fastJpeg));
                if (fastDecoded == null || fastDecoded.getWidth() > 1024 || fastDecoded.getHeight() > 576) throw new AssertionError("Fast JPEG resize/decode failed");

                byte[] qualityJpeg = encoder.encode(testFrame, StreamProfile.quality());
                BufferedImage qualityDecoded = ImageIO.read(new ByteArrayInputStream(qualityJpeg));
                if (qualityDecoded == null || qualityDecoded.getWidth() != 1920 || qualityDecoded.getHeight() != 1080)
                    throw new AssertionError("High-quality preset must preserve Full HD input resolution");
                if (qualityJpeg.length == 0 || qualityJpeg.length > MAX_FRAME_BYTES)
                    throw new AssertionError("High-quality JPEG frame exceeds the protocol limit");

                byte[] ultraJpeg = encoder.encode(testFrame, StreamProfile.ultra());
                BufferedImage ultraDecoded = ImageIO.read(new ByteArrayInputStream(ultraJpeg));
                if (ultraDecoded == null || ultraDecoded.getWidth() != 1920 || ultraDecoded.getHeight() != 1080)
                    throw new AssertionError("Ultra preset must not upscale a Full HD host display");
                if (ultraJpeg.length > MAX_FRAME_BYTES) throw new AssertionError("Ultra JPEG frame exceeds the protocol limit");

                // Compare against v0.7.4's JPEG quality 0.59 at the same resolution.
                StreamProfile previousQuality = new StreamProfile("Previous quality baseline", 1920, 1080, 12, 0.59f);
                BufferedImage previousDecoded = ImageIO.read(new ByteArrayInputStream(encoder.encode(testFrame, previousQuality)));
                if (previousDecoded == null || meanAbsolutePixelError(testFrame, qualityDecoded) >= meanAbsolutePixelError(testFrame, previousDecoded))
                    throw new AssertionError("High-quality preset did not improve measured JPEG fidelity over v0.7.4 baseline");
            }

            if (!TailscaleManager.isTailscaleIpv4("100.64.0.1") || TailscaleManager.isTailscaleIpv4("100.10.0.1")) {
                throw new AssertionError("Tailscale IPv4 range validation failed");
            }
            List<TailscalePeer> peers = TailscaleManager.parsePeers(
                    "100.64.0.10 test-host demo@example.invalid windows -\n100.64.0.11 test-viewer demo@example.invalid windows active; direct 192.0.2.20:41641",
                    "100.64.0.10");
            if (peers.size() != 2 || !peers.get(0).self() || !peers.get(1).ip().equals("100.64.0.11")) {
                throw new AssertionError("Tailscale peer list parsing failed");
            }

            if (!hasArgument(new String[]{"--restore-remote-control-permission"}, "--restore-remote-control-permission"))
                throw new AssertionError("Explicit UAC restart consent flag was not recognized");
            if (hasArgument(new String[]{"--unrelated-argument"}, "--restore-remote-control-permission"))
                throw new AssertionError("Remote-control consent must not be inferred from unrelated arguments");

            long nowNanos = System.nanoTime();
            if (!shouldPauseRemoteForHostInput(true, true, true))
                throw new AssertionError("Host physical input must take priority when recently active");
            if (shouldPauseRemoteForHostInput(true, true, false))
                throw new AssertionError("Remote input must resume after the host becomes idle");
            if (shouldPauseRemoteForHostInput(true, false, true))
                throw new AssertionError("A failed monitor must not permanently block remote input");
            if (shouldPauseRemoteForHostInput(false, true, true))
                throw new AssertionError("Disabling host priority must allow remote input");
        } finally {
            Arrays.fill(password, '\0');
            try (var paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
            }
        }
    }

    private static double meanAbsolutePixelError(BufferedImage reference, BufferedImage candidate) {
        if (reference.getWidth() != candidate.getWidth() || reference.getHeight() != candidate.getHeight())
            return Double.POSITIVE_INFINITY;
        long error = 0;
        long samples = 0;
        // Sample every second pixel to keep the self-test fast while covering UI edges and gradients.
        for (int y = 0; y < reference.getHeight(); y += 2) {
            for (int x = 0; x < reference.getWidth(); x += 2) {
                int a = reference.getRGB(x, y);
                int b = candidate.getRGB(x, y);
                error += Math.abs(((a >>> 16) & 255) - ((b >>> 16) & 255));
                error += Math.abs(((a >>> 8) & 255) - ((b >>> 8) & 255));
                error += Math.abs((a & 255) - (b & 255));
                samples += 3;
            }
        }
        return samples == 0 ? 0 : error / (double) samples;
    }

    private static String timestamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    private static void logTo(JTextArea area, String message) {
        if (area == null) return;
        SwingUtilities.invokeLater(() -> {
            area.append("[" + timestamp() + "] " + message + System.lineSeparator());
            int extra = area.getDocument().getLength() - 250_000;
            if (extra > 0) {
                try { area.getDocument().remove(0, extra); }
                catch (Exception ignored) { }
            }
            area.setCaretPosition(area.getDocument().getLength());
        });
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
    }

    private static String normalizeFingerprint(String value) {
        return value == null ? "" : value.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
    }

    private static SSLContext createServerContext(File keyStoreFile, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStoreFile.toPath())) { keyStore.load(in, password); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private static X509Certificate readCertificate(File keyStoreFile, char[] password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStoreFile.toPath())) { keyStore.load(in, password); }
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            Certificate certificate = keyStore.getCertificate(aliases.nextElement());
            if (certificate instanceof X509Certificate x509) return x509;
        }
        throw new GeneralSecurityException("No X.509 certificate found in the identity store.");
    }

    // Minimal DER encoder used only to create a self-signed identity without keytool/OpenSSL.
    private static byte[] der(int tag, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 8);
        out.write(tag);
        int length = content.length;
        if (length < 128) out.write(length);
        else {
            int count = 0, value = length;
            while (value > 0) { count++; value >>>= 8; }
            out.write(0x80 | count);
            for (int i = count - 1; i >= 0; i--) out.write((length >>> (8 * i)) & 0xFF);
        }
        out.write(content);
        return out.toByteArray();
    }
    private static byte[] join(byte[]... values) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) out.write(value);
        return out.toByteArray();
    }
    private static byte[] seq(byte[]... values) throws IOException { return der(0x30, join(values)); }
    private static byte[] set(byte[]... values) throws IOException { return der(0x31, join(values)); }
    private static byte[] integer(java.math.BigInteger value) throws IOException { return der(0x02, value.toByteArray()); }
    private static byte[] oid(String dotted) throws IOException {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeBase128(body, Long.parseLong(parts[0]) * 40 + Long.parseLong(parts[1]));
        for (int i = 2; i < parts.length; i++) writeBase128(body, Long.parseLong(parts[i]));
        return der(0x06, body.toByteArray());
    }
    private static void writeBase128(ByteArrayOutputStream out, long value) {
        byte[] stack = new byte[10];
        int pos = stack.length - 1;
        stack[pos] = (byte) (value & 0x7F);
        value >>>= 7;
        while (value > 0) { stack[--pos] = (byte) ((value & 0x7F) | 0x80); value >>>= 7; }
        out.write(stack, pos, stack.length - pos);
    }
    private static byte[] utcTime(Instant instant) throws IOException {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
        return der(0x17, f.format(instant).getBytes(StandardCharsets.US_ASCII));
    }

    private static X509Certificate createHostCertificate(File outputFile, char[] password) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        byte[] algorithm = seq(oid("1.2.840.113549.1.1.11"), der(0x05, new byte[0]));
        byte[] name = seq(set(seq(oid("2.5.4.3"), der(0x0C, "Nexxa Remote Host".getBytes(StandardCharsets.UTF_8)))));
        Instant now = Instant.now();
        byte[] validity = seq(utcTime(now.minusSeconds(300)), utcTime(now.plusSeconds(315360000L)));
        java.math.BigInteger serial = new java.math.BigInteger(160, new SecureRandom()).setBit(0);
        byte[] tbs = seq(integer(serial), algorithm, name, validity, name, pair.getPublic().getEncoded());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] certBytes = seq(tbs, algorithm, der(0x03, join(new byte[]{0}, signer.sign())));
        X509Certificate cert = (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certBytes));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry("nexxa", pair.getPrivate(), password, new Certificate[]{cert});
        Files.createDirectories(outputFile.toPath().toAbsolutePath().getParent());
        Path tmp = Path.of(outputFile.getAbsolutePath() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) { store.store(out, password); }
        try { Files.move(tmp, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        return cert;
    }

    private static SSLContext createPinnedClientContext(String expectedFingerprint) throws Exception {
        String expected = normalizeFingerprint(expectedFingerprint);
        if (expected.length() != 64) throw new IllegalArgumentException("The invitation has an invalid SHA-256 certificate fingerprint.");
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("Host certificate is missing.");
                try {
                    String actual = normalizeFingerprint(fingerprint(chain[0]));
                    if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII)))
                        throw new java.security.cert.CertificateException("Host fingerprint does not match. Connection refused.");
                } catch (java.security.cert.CertificateException e) { throw e; }
                catch (Exception e) { throw new java.security.cert.CertificateException("Could not verify host certificate.", e); }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trust}, new SecureRandom());
        return context;
    }

    private static void setModernTls(SSLSocket socket) {
        List<String> protocols = new ArrayList<>();
        for (String protocol : socket.getSupportedProtocols())
            if (protocol.equals("TLSv1.3") || protocol.equals("TLSv1.2")) protocols.add(protocol);
        socket.setEnabledProtocols(protocols.toArray(String[]::new));
    }
    private static void setModernTls(SSLServerSocket socket) {
        List<String> protocols = new ArrayList<>();
        for (String protocol : socket.getSupportedProtocols())
            if (protocol.equals("TLSv1.3") || protocol.equals("TLSv1.2")) protocols.add(protocol);
        socket.setEnabledProtocols(protocols.toArray(String[]::new));
    }

    private static Path appDataDirectory() {
        String roaming = System.getenv("APPDATA");
        Path base = roaming == null || roaming.isBlank() ? Path.of(System.getProperty("user.home"), ".nexxa") : Path.of(roaming, "NexxaRemote");
        return base.toAbsolutePath();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Protects the PKCS#12 password with Windows DPAPI for the current Windows user.
     * The password is sent to PowerShell through stdin, not as a command-line argument.
     * On non-Windows systems used for development/testing, it is Base64-wrapped and
     * the containing file is restricted to the current user where POSIX permissions exist.
     */
    private static String protectIdentitySecret(String secret) throws Exception {
        if (!isWindows()) {
            return "LOCAL:" + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
        }
        String script = "$ErrorActionPreference='Stop'; " +
                "$plain=[Console]::In.ReadToEnd(); " +
                "$secure=ConvertTo-SecureString -String $plain -AsPlainText -Force; " +
                "Write-Output ($secure | ConvertFrom-SecureString)";
        return runPowerShellWithInput(script, secret).trim();
    }

    private static String unprotectIdentitySecret(String protectedValue) throws Exception {
        if (protectedValue.startsWith("LOCAL:")) {
            return new String(Base64.getDecoder().decode(protectedValue.substring(6)), StandardCharsets.UTF_8);
        }
        if (!isWindows()) throw new GeneralSecurityException("This host identity password is protected for its original Windows user account.");
        String script = "$ErrorActionPreference='Stop'; " +
                "$encrypted=([Console]::In.ReadToEnd()).Trim(); " +
                "$secure=ConvertTo-SecureString -String $encrypted; " +
                "$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure); " +
                "try { [Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)) } " +
                "finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }";
        return runPowerShellWithInput(script, protectedValue);
    }

    private static String runPowerShellWithInput(String script, String input) throws Exception {
        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) { stream.transferTo(output); }
            catch (IOException ignored) { }
        }, "Nexxa-DPAPI-Output");
        reader.setDaemon(true);
        reader.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Windows DPAPI operation timed out.");
        }
        reader.join(1500);
        String result = output.toString(StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0 || result.isBlank()) {
            throw new GeneralSecurityException("Windows could not protect/unprotect the Nexxa identity password: " + result);
        }
        return result;
    }

    private static void restrictFileToCurrentUser(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Windows uses the current user's AppData ACL; DPAPI protects the password file.
        }
    }

    private static final class IdentityStore {
        final File keyStoreFile;
        final X509Certificate certificate;
        final String fingerprint;
        final SSLContext serverContext;

        private IdentityStore(File file, X509Certificate cert, SSLContext context) throws Exception {
            this.keyStoreFile = file;
            this.certificate = cert;
            this.fingerprint = fingerprint(cert);
            this.serverContext = context;
        }

        static IdentityStore loadOrCreate(JTextArea console) throws Exception {
            Path dir = appDataDirectory();
            Files.createDirectories(dir);
            Path storePath = dir.resolve("host-identity.p12");
            Path protectedSecretPath = dir.resolve("host-identity-password.dpapi");
            Path legacySecretPath = dir.resolve("host-identity-password.txt");

            String secret;
            boolean createNewIdentity = !Files.exists(storePath);
            if (Files.exists(protectedSecretPath)) {
                secret = unprotectIdentitySecret(Files.readString(protectedSecretPath, StandardCharsets.UTF_8).trim());
            } else if (Files.exists(legacySecretPath)) {
                // Migrate the earlier prototype's adjacent plaintext password file to DPAPI.
                secret = Files.readString(legacySecretPath, StandardCharsets.UTF_8).trim();
                String protectedValue = protectIdentitySecret(secret);
                Files.writeString(protectedSecretPath, protectedValue, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                restrictFileToCurrentUser(protectedSecretPath);
                Files.deleteIfExists(legacySecretPath);
                logTo(console, "Migrated the existing host identity password to Windows DPAPI protection.");
            } else {
                secret = randomToken(32);
                createNewIdentity = true;
            }

            char[] password = secret.toCharArray();
            try {
                if (createNewIdentity) {
                    createHostCertificate(storePath.toFile(), password);
                    logTo(console, "Created a new persistent TLS identity in " + dir + ". The private key remains on this Windows account.");
                }
                if (!Files.exists(protectedSecretPath)) {
                    String protectedValue = protectIdentitySecret(secret);
                    Files.writeString(protectedSecretPath, protectedValue, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    restrictFileToCurrentUser(protectedSecretPath);
                }
                Files.deleteIfExists(legacySecretPath);
                restrictFileToCurrentUser(storePath);
                X509Certificate cert = readCertificate(storePath.toFile(), password);
                SSLContext context = createServerContext(storePath.toFile(), password);
                return new IdentityStore(storePath.toFile(), cert, context);
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }

    /**
     * Video presets balance detail against encoder cost and network bandwidth.
     * Dimensions are upper bounds: smaller host displays are always captured at native size.
     */
    private record StreamProfile(String label, int maxWidth, int maxHeight, int fps, float jpegQuality) {
        static StreamProfile fast() { return new StreamProfile("Low latency • 20 FPS • 1024×576", 1024, 576, 20, 0.48f); }
        static StreamProfile balanced() { return new StreamProfile("Balanced • 18 FPS • 1600×900", 1600, 900, 18, 0.66f); }
        static StreamProfile quality() { return new StreamProfile("High quality • 12 FPS • Full HD", 1920, 1080, 12, 0.86f); }
        static StreamProfile ultra() { return new StreamProfile("Ultra detail • 8 FPS • up to 1440p", 2560, 1440, 8, 0.92f); }
        @Override public String toString() { return label; }
    }

    /** Keeps one JPEG writer alive for the lifetime of a screen stream. */
    private static final class JpegEncoder implements AutoCloseable {
        private final ImageWriter writer;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(96 * 1024);

        JpegEncoder() throws IOException {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) throw new IOException("This Java runtime has no JPEG encoder.");
            writer = writers.next();
        }

        byte[] encode(BufferedImage source, StreamProfile profile) throws IOException {
            double scale = Math.min(1.0, Math.min(profile.maxWidth() / (double) source.getWidth(),
                    profile.maxHeight() / (double) source.getHeight()));
            BufferedImage image = source;
            if (scale < 1.0 || source.getType() != BufferedImage.TYPE_INT_RGB) {
                int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
                image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    // Bicubic downsampling preserves fine UI text and diagonal edges better
                    // than nearest-neighbour, which made scaled desktop text look rough.
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
                    graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
                    graphics.drawImage(source, 0, 0, width, height, null);
                } finally { graphics.dispose(); }
            }

            bytes.reset();
            try (MemoryCacheImageOutputStream imageOut = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(imageOut);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(profile.jpegQuality());
                }
                writer.write(null, new IIOImage(image, null, null), param);
                imageOut.flush();
            } finally {
                writer.reset();
            }
            return bytes.toByteArray();
        }

        @Override public void close() { writer.dispose(); }
    }

    private static String createInvite(String host, int port, String fingerprint, String accessKey) {
        return "nexxa://connect?host=" + enc(host) + "&port=" + port + "&fp=" + enc(fingerprint) + "&key=" + enc(accessKey);
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new HashMap<>();
        if (query == null) return values;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            values.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
    private record Invite(String host, int port, String fingerprint, String accessKey) { }
    private static Invite parseInvite(String text) {
        try {
            URI uri = URI.create(text.trim());
            if (!"nexxa".equalsIgnoreCase(uri.getScheme()) || !"connect".equalsIgnoreCase(uri.getHost()))
                throw new IllegalArgumentException("This is not a Nexxa invitation link.");
            Map<String, String> q = parseQuery(uri.getRawQuery());
            String host = q.getOrDefault("host", "");
            int port = Integer.parseInt(q.getOrDefault("port", String.valueOf(DEFAULT_PORT)));
            String fp = q.getOrDefault("fp", "");
            String key = q.getOrDefault("key", "");
            if (host.isBlank() || port < 1 || port > 65535 || normalizeFingerprint(fp).length() != 64 || key.length() < 32)
                throw new IllegalArgumentException("Invitation is incomplete or invalid.");
            return new Invite(host, port, fp, key);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Could not read the Nexxa invitation link.", e); }
    }

    private static String localClipboardText() {
        try {
            if (Toolkit.getDefaultToolkit().getSystemClipboard().isDataFlavorAvailable(DataFlavor.stringFlavor))
                return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        } catch (Exception ignored) { }
        return null;
    }
    private static void setLocalClipboardText(String text) {
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null); }
        catch (Exception ignored) { }
    }

    private static void writeTextMessage(DataOutputStream out, int type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CLIPBOARD_BYTES) return;
        synchronized (out) { out.writeByte(type); out.writeInt(bytes.length); out.write(bytes); out.flush(); }
    }
    private static String readTextMessage(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_CLIPBOARD_BYTES) throw new IOException("Clipboard message is too large.");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Incomplete clipboard message.");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int mouseMask(int awtButton) {
        return switch (awtButton) {
            case MouseEvent.BUTTON1 -> InputEvent.BUTTON1_DOWN_MASK;
            case MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK;
            case MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK;
            default -> 0;
        };
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    /**
     * Returns true only when the native physical-input monitor is healthy and the host
     * has actually used a physical input device recently. If the monitor is unavailable,
     * remote control remains usable (with a warning) instead of being blocked forever.
     */
    private static boolean shouldPauseRemoteForHostInput(boolean priorityEnabled, boolean monitorHealthy,
                                                         boolean localInputRecent) {
        return priorityEnabled && monitorHealthy && localInputRecent;
    }

    private static void installModernTheme() {
        Color field = new Color(28, 39, 56);
        Color selected = new Color(40, 58, 82);
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Button.background", PANEL_ALT);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.disabledText", new Color(153, 171, 192));
        UIManager.put("Button.font", new Font("Segoe UI", Font.BOLD, 13));
        UIManager.put("TextField.background", field);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", Color.WHITE);
        UIManager.put("PasswordField.background", field);
        UIManager.put("PasswordField.foreground", TEXT);
        UIManager.put("PasswordField.caretForeground", Color.WHITE);
        UIManager.put("TextArea.background", new Color(10, 15, 24));
        UIManager.put("TextArea.foreground", new Color(196, 211, 230));
        UIManager.put("ComboBox.background", field);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("CheckBox.background", PANEL);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("TabbedPane.background", BG);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.selected", selected);
        UIManager.put("TabbedPane.contentAreaColor", PANEL);
        UIManager.put("ScrollPane.background", PANEL);
        UIManager.put("List.background", PANEL_ALT);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("Table.background", PANEL_ALT);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.selectionBackground", selected);
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("ToolTip.background", PANEL_ALT);
        UIManager.put("ToolTip.foreground", TEXT);
    }

    private static JPanel cardPanel(String title, String subtitle) {
        JPanel panel = new JPanel(new BorderLayout(8, 10));
        panel.setBackground(PANEL);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(48, 65, 89)), new EmptyBorder(15, 16, 15, 16)));
        JPanel heading = new JPanel(new GridLayout(0, 1, 2, 3));
        heading.setOpaque(false);
        JLabel t = new JLabel(title); t.setForeground(TEXT); t.setFont(new Font("Segoe UI", Font.BOLD, 18));
        JLabel sub = new JLabel(subtitle); sub.setForeground(MUTED); sub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        heading.add(t); heading.add(sub); panel.add(heading, BorderLayout.NORTH);
        return panel;
    }

    private static final class NexxaButtonUI extends javax.swing.plaf.basic.BasicButtonUI {
        @Override protected void paintText(Graphics graphics, AbstractButton button, Rectangle textRect, String text) {
            Color previous = graphics.getColor();
            graphics.setFont(button.getFont());
            graphics.setColor(button.isEnabled() ? button.getForeground() : new Color(164, 179, 198));
            FontMetrics metrics = graphics.getFontMetrics(button.getFont());
            int baseline = textRect.y + metrics.getAscent();
            javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(
                    graphics, text, button.getDisplayedMnemonicIndex(), textRect.x, baseline);
            graphics.setColor(previous);
        }
    }

    private static void styleScrollPane(JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);
        for (JScrollBar bar : List.of(scroll.getVerticalScrollBar(), scroll.getHorizontalScrollBar())) {
            bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
                @Override protected void configureScrollBarColors() {
                    thumbColor = new Color(65, 84, 112);
                    thumbDarkShadowColor = new Color(43, 57, 77);
                    thumbHighlightColor = new Color(91, 113, 145);
                    trackColor = new Color(12, 18, 28);
                    trackHighlightColor = new Color(12, 18, 28);
                }
                @Override protected JButton createDecreaseButton(int orientation) { return zeroButton(); }
                @Override protected JButton createIncreaseButton(int orientation) { return zeroButton(); }
                private JButton zeroButton() {
                    JButton button = new JButton(); button.setPreferredSize(new Dimension(0, 0));
                    button.setMinimumSize(new Dimension(0, 0)); button.setMaximumSize(new Dimension(0, 0));
                    return button;
                }
            });
        }
    }

    private static void styleButton(JButton button, boolean primary) {
        button.setUI(new NexxaButtonUI());
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(true);
        button.setRolloverEnabled(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(primary ? new Color(91, 164, 255) : new Color(65, 82, 108)),
                new EmptyBorder(9, 14, 9, 14)));
        button.setBackground(primary ? new Color(43, 119, 220) : new Color(38, 52, 73));
        button.setForeground(Color.WHITE);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.addPropertyChangeListener("enabled", event ->
                button.setForeground(button.isEnabled() ? Color.WHITE : new Color(164, 179, 198)));
        button.setMargin(new Insets(4, 8, 4, 8));
    }

    private static void styleInput(JComponent component) {
        component.setOpaque(true);
        component.setBackground(new Color(28, 39, 56));
        component.setForeground(TEXT);
        component.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        component.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(59, 77, 103)), new EmptyBorder(7, 9, 7, 9)));
        if (component instanceof JTextField field) field.setCaretColor(Color.WHITE);
        if (component instanceof JPasswordField field) field.setCaretColor(Color.WHITE);
    }

    private static void styleCheckBox(JCheckBox box) {
        box.setOpaque(false);
        box.setForeground(TEXT);
        box.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        box.setFocusPainted(false);
    }

    private static JPanel sectionPanel(String title, String subtitle) {
        return cardPanel(title, subtitle);
    }

    private static final class ModernTabbedPaneUI extends javax.swing.plaf.basic.BasicTabbedPaneUI {
        @Override protected void installDefaults() {
            super.installDefaults();
            tabInsets = new Insets(10, 18, 10, 18);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
            contentBorderInsets = new Insets(1, 1, 1, 1);
            tabAreaInsets = new Insets(0, 0, 0, 0);
        }
        @Override protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {
            g.setColor(selected ? PANEL_ALT : BG);
            g.fillRect(x, y, w, h);
        }
        @Override protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {
            g.setColor(selected ? ACCENT : new Color(48, 65, 89));
            g.drawRect(x, y, w, h);
            if (selected) { g.setColor(ACCENT); g.fillRect(x + 3, y + h - 3, Math.max(0, w - 6), 2); }
        }
    }

    @SuppressWarnings("serial")
    private static final class MainWindow extends JFrame {
        private static final long serialVersionUID = 1L;
        private final JTextArea console = new JTextArea(7, 100);
        private final JLabel tailscaleStatus = new JLabel("Tailscale: starting...");
        private final JLabel tailscaleIp = new JLabel("Address: checking...");
        private final TailscaleManager tailscale;
        private final HostPanel hostPanel;
        private final ViewerPanel viewerPanel;
        private JPanel rootPanel;
        private JTabbedPane tabs;
        private boolean viewerConnectedLayout;
        private IdentityStore identity;
        private boolean closing;
        private boolean fullScreen;
        private Rectangle windowedBounds;
        private int windowedState = Frame.NORMAL;

        MainWindow() {
            super(APP_NAME + " " + APP_VERSION);
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            setMinimumSize(new Dimension(1120, 740));
            setSize(1380, 900);
            setLocationRelativeTo(null);
            getContentPane().setBackground(BG);
            console.setEditable(false);
            console.setLineWrap(false);
            console.setBackground(new Color(10, 15, 24));
            console.setForeground(new Color(196, 211, 230));
            console.setCaretColor(Color.WHITE);
            console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            console.setBorder(new EmptyBorder(8, 9, 8, 9));
            tailscale = new TailscaleManager(console, tailscaleStatus, tailscaleIp);
            hostPanel = new HostPanel(console, tailscale, this);
            viewerPanel = new ViewerPanel(console, tailscale, this);
            buildUi();
            addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { closeApplication(); }
            });
            Thread identityThread = new Thread(() -> {
                try {
                    IdentityStore found = IdentityStore.loadOrCreate(console);
                    identity = found;
                    SwingUtilities.invokeLater(() -> hostPanel.setIdentity(found));
                    logTo(console, "TLS identity ready. SHA-256 fingerprint: " + found.fingerprint);
                } catch (Exception ex) {
                    logTo(console, "ERROR creating local TLS identity: " + ex.getMessage());
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "Could not create the host identity.\n" + ex.getMessage(), APP_NAME, JOptionPane.ERROR_MESSAGE));
                }
            }, "Nexxa-Identity-Setup");
            identityThread.setDaemon(true);
            identityThread.start();
        }

        private void buildUi() {
            rootPanel = new JPanel(new BorderLayout(12, 12));
            rootPanel.setBackground(BG);
            rootPanel.setBorder(new EmptyBorder(14, 16, 14, 16));

            JPanel top = new JPanel(new BorderLayout(8, 8));
            top.setOpaque(false);
            JPanel brand = new JPanel(new GridLayout(0, 1, 2, 3)); brand.setOpaque(false);
            JLabel name = new JLabel("NEXXA REMOTE");
            name.setForeground(TEXT); name.setFont(new Font("Segoe UI", Font.BOLD, 27));
            JLabel tagline = new JLabel("Private remote desktop  •  Tailscale-connected  •  No subscription");
            tagline.setForeground(MUTED); tagline.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            brand.add(name); brand.add(tagline);
            JPanel network = new JPanel(new GridLayout(0, 1, 4, 4)); network.setOpaque(false);
            tailscaleStatus.setForeground(new Color(255, 205, 106));
            tailscaleStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
            tailscaleIp.setForeground(MUTED); tailscaleIp.setFont(new Font("Consolas", Font.PLAIN, 12));
            network.add(tailscaleStatus); network.add(tailscaleIp);
            top.add(brand, BorderLayout.WEST); top.add(network, BorderLayout.EAST);
            top.setBorder(new EmptyBorder(2, 2, 6, 2));
            rootPanel.add(top, BorderLayout.NORTH);

            tabs = new JTabbedPane();
            tabs.addTab("  SHARE THIS PC  ", hostPanel);
            tabs.addTab("  CONNECT  ", viewerPanel);
            tabs.setUI(new ModernTabbedPaneUI());
            tabs.setFont(new Font("Segoe UI", Font.BOLD, 12));
            tabs.setForeground(TEXT);
            tabs.setBackground(PANEL);
            tabs.setBorder(BorderFactory.createLineBorder(new Color(48, 65, 89)));
            rootPanel.add(tabs, BorderLayout.CENTER);
            setContentPane(rootPanel);

            // Escape always leaves full-screen viewer mode.
            getRootPane().registerKeyboardAction(e -> exitFullScreen(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

            logTo(console, "Nexxa Remote " + APP_VERSION + " started.");
            logTo(console, "The Activity Console is available on the Share this PC page; it is hidden while viewing a remote desktop.");
            logTo(console, "Remote input is protected by host-side local-input priority. The host's physical keyboard and mouse take precedence.");
        }

        /**
         * Expands the connected viewer to use the entire application client area.
         * The host branding and tab strip are removed only while a remote session is active.
         * ScreenPanel keeps aspect-ratio-preserving FIT scaling, so the full host desktop
         * remains visible without cropping or distorting its edges.
         */
        void setViewerConnectedLayout(boolean connected) {
            if (viewerConnectedLayout == connected) return;

            if (connected) {
                viewerConnectedLayout = true;
                viewerPanel.setBorder(new EmptyBorder(2, 2, 2, 2));
                setContentPane(viewerPanel);
                logTo(console, "Connected viewer expanded to full application workspace.");
            } else {
                // Exit exclusive full-screen first, then restore the normal host/connect UI.
                if (fullScreen) exitFullScreen();
                viewerPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
                setContentPane(rootPanel);
                if (tabs.indexOfComponent(viewerPanel) < 0) {
                    tabs.addTab("  CONNECT  ", viewerPanel);
                }
                tabs.setSelectedComponent(viewerPanel);
                viewerConnectedLayout = false;
                logTo(console, "Viewer disconnected; normal Nexxa workspace restored.");
            }

            revalidate();
            repaint();
        }

        private static void openUrl(String value) {
            try { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(value)); }
            catch (Exception ignored) { }
        }

        void openInvitation(String invitation) {
            try {
                parseInvite(invitation);
                tabs.setSelectedComponent(viewerPanel);
                viewerPanel.setInvitation(invitation);
                logTo(console, "Nexxa invitation opened from a nexxa:// link.");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "The Nexxa invitation link is invalid.\n" + e.getMessage(),
                        APP_NAME, JOptionPane.WARNING_MESSAGE);
            }
        }

        void toggleFullScreen() {
            if (fullScreen) { exitFullScreen(); return; }
            GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            windowedBounds = getBounds();
            windowedState = getExtendedState();
            fullScreen = true;
            device.setFullScreenWindow(this);
            viewerPanel.setFullScreenButtonText("Exit full screen");
        }

        private void exitFullScreen() {
            if (!fullScreen) return;
            GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            device.setFullScreenWindow(null);
            fullScreen = false;
            if (windowedBounds != null) setBounds(windowedBounds);
            setExtendedState(windowedState);
            viewerPanel.setFullScreenButtonText("Full screen");
        }

        void inheritTailscaleOwnership(boolean serviceOwned, boolean connectionOwned) {
            tailscale.inheritOwnership(serviceOwned, connectionOwned);
        }

        /** A deliberately sized confirmation window; JOptionPane's native layout clipped the buttons on some Windows scales. */
        private boolean confirmAdministratorRestart() {
            JDialog dialog = new JDialog(this, APP_NAME + " — Administrator access", Dialog.ModalityType.APPLICATION_MODAL);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setResizable(false);

            JPanel root = new JPanel(new BorderLayout(0, 18));
            root.setBackground(BG);
            root.setBorder(new EmptyBorder(22, 24, 20, 24));

            JPanel heading = new JPanel(new BorderLayout(14, 0));
            heading.setOpaque(false);
            JLabel warningIcon = new JLabel("!");
            warningIcon.setHorizontalAlignment(SwingConstants.CENTER);
            warningIcon.setPreferredSize(new Dimension(42, 42));
            warningIcon.setOpaque(true);
            warningIcon.setBackground(new Color(126, 82, 25));
            warningIcon.setForeground(new Color(255, 224, 153));
            warningIcon.setFont(new Font("Segoe UI", Font.BOLD, 25));
            warningIcon.setBorder(BorderFactory.createLineBorder(new Color(205, 151, 63)));
            heading.add(warningIcon, BorderLayout.WEST);

            JPanel headingText = new JPanel(new GridLayout(0, 1, 0, 5));
            headingText.setOpaque(false);
            JLabel title = new JLabel("Enable administrator access?");
            title.setForeground(TEXT);
            title.setFont(new Font("Segoe UI", Font.BOLD, 19));
            JLabel subtitle = new JLabel("Nexxa must restart after you approve the Windows UAC prompt.");
            subtitle.setForeground(MUTED);
            subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            headingText.add(title);
            headingText.add(subtitle);
            heading.add(headingText, BorderLayout.CENTER);
            root.add(heading, BorderLayout.NORTH);

            String message = "After approval, the Nexxa host process runs with administrator privileges. " +
                    "A trusted viewer who has been granted remote keyboard and mouse control can then operate " +
                    "applications available at that Windows integrity level.\n\n" +
                    "Windows secure-desktop UAC prompts and credential dialogs still require local approval. " +
                    "Nexxa does not bypass UAC or silently approve elevation.\n\n" +
                    "Any active Nexxa session will disconnect. After the elevated application opens, start sharing " +
                    "again and send the viewer a newly generated invitation. Only enable this for a viewer you trust.";
            JTextArea body = new JTextArea(message);
            body.setEditable(false);
            body.setFocusable(false);
            body.setLineWrap(true);
            body.setWrapStyleWord(true);
            body.setRows(7);
            body.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            body.setForeground(TEXT);
            body.setBackground(PANEL);
            body.setBorder(new EmptyBorder(14, 15, 14, 15));
            body.setHighlighter(null);

            JPanel bodyCard = new JPanel(new BorderLayout());
            bodyCard.setBackground(PANEL);
            bodyCard.setBorder(BorderFactory.createLineBorder(new Color(48, 65, 89)));
            bodyCard.add(body, BorderLayout.CENTER);
            root.add(bodyCard, BorderLayout.CENTER);

            boolean[] approved = {false};
            JButton cancel = new JButton("Cancel");
            JButton enable = new JButton("Enable administrator access");
            styleButton(cancel, false);
            styleButton(enable, true);
            cancel.setPreferredSize(new Dimension(116, 42));
            enable.setPreferredSize(new Dimension(226, 42));
            cancel.addActionListener(event -> dialog.dispose());
            enable.addActionListener(event -> { approved[0] = true; dialog.dispose(); });

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            buttons.setOpaque(false);
            buttons.add(cancel);
            buttons.add(enable);
            root.add(buttons, BorderLayout.SOUTH);

            dialog.setContentPane(root);
            dialog.getRootPane().setDefaultButton(cancel);
            dialog.getRootPane().setBorder(BorderFactory.createLineBorder(new Color(63, 83, 111)));
            dialog.setSize(new Dimension(760, 430));
            dialog.setMinimumSize(new Dimension(760, 430));
            dialog.setLocationRelativeTo(this);

            // Keep the complete dialog inside the usable monitor area (including taskbar/DPI scaling).
            Rectangle usable = dialog.getGraphicsConfiguration().getBounds();
            Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());
            usable.x += screenInsets.left;
            usable.y += screenInsets.top;
            usable.width -= screenInsets.left + screenInsets.right;
            usable.height -= screenInsets.top + screenInsets.bottom;
            int width = Math.min(dialog.getWidth(), usable.width - 24);
            int height = Math.min(dialog.getHeight(), usable.height - 24);
            dialog.setSize(Math.max(640, width), Math.max(360, height));
            dialog.setLocation(usable.x + (usable.width - dialog.getWidth()) / 2,
                    usable.y + (usable.height - dialog.getHeight()) / 2);
            dialog.setVisible(true);
            return approved[0];
        }

        void enableAdministratorMode() {
            if (!isWindows()) {
                JOptionPane.showMessageDialog(this, "Administrator mode is currently supported on Windows only.", APP_NAME, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (runningAsAdministrator) {
                JOptionPane.showMessageDialog(this, "Nexxa is already running with administrator privileges.", APP_NAME, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (!confirmAdministratorRestart()) return;

            hostPanel.setAdministratorActionEnabled(false);
            logTo(console, "Requesting administrator access through the standard Windows UAC prompt...");
            // Preserve only a permission that the host explicitly enabled before approving elevation.
            // Fresh app launches still default to view-only; a new invitation is required after restart.
            final boolean preserveRemoteControl = hostPanel.isRemoteControlAllowed();
            Thread worker = new Thread(() -> {
                String failure = launchElevatedInstance(tailscale.wasServiceStartedByApp(),
                        tailscale.wasConnectionStartedByApp(), preserveRemoteControl);
                SwingUtilities.invokeLater(() -> {
                    if (!failure.isBlank()) {
                        hostPanel.setAdministratorActionEnabled(true);
                        logTo(console, "Administrator restart was not started: " + failure);
                        JOptionPane.showMessageDialog(this, failure, APP_NAME, JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    logTo(console, "Windows started the elevated Nexxa instance. Shutting down this standard instance while preserving Tailscale ownership...");
                    restartShutdownPreservingTailscale();
                });
            }, "Nexxa-Administrator-Restart");
            worker.setDaemon(true);
            worker.start();
        }

        private void restartShutdownPreservingTailscale() {
            if (closing) return;
            closing = true;
            exitFullScreen();
            hostPanel.stopHost();
            viewerPanel.shutdown();
            setVisible(false);
            Thread shutdown = new Thread(() -> {
                // The elevated replacement inherits these ownership flags and will stop Tailscale on its exit.
                tailscale.stopForApplication(false);
                SwingUtilities.invokeLater(() -> { dispose(); System.exit(0); });
            }, "Nexxa-Elevation-Handoff");
            shutdown.setDaemon(false);
            shutdown.start();
        }

        void startTailscale() { tailscale.startForApplication(); }

        private void closeApplication() {
            if (closing) return;
            closing = true;
            exitFullScreen();
            hostPanel.stopHost();
            viewerPanel.shutdown();
            boolean manage = hostPanel.shouldStopTailscaleOnExit();
            setVisible(false);
            Thread shutdown = new Thread(() -> {
                tailscale.stopForApplication(manage);
                SwingUtilities.invokeLater(() -> { dispose(); System.exit(0); });
            }, "Nexxa-App-Shutdown");
            shutdown.setDaemon(false);
            shutdown.start();
        }
    }

    private record TailscalePeer(String ip, String hostname, String user, String os, String state, boolean self) {
        @Override public String toString() {
            String label = self ? "THIS PC" : state.toUpperCase(Locale.ROOT);
            return String.format("%-18s  %-23s  %s", ip, hostname, label);
        }
    }

    private static final class TailscaleManager {
        private final JTextArea console;
        private final JLabel statusLabel;
        private final JLabel ipLabel;
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Nexxa-Tailscale-Monitor");
            t.setDaemon(true);
            return t;
        });
        private volatile String cli;
        private volatile String ip = "";
        private volatile String lastStatus = "";
        private volatile List<TailscalePeer> peers = List.of();
        private volatile boolean serviceStartedByApp;
        private volatile boolean connectedByApp;
        private volatile boolean stopServiceOnExitRequested;
        private volatile boolean inheritedOwnership;
        private final AtomicBoolean started = new AtomicBoolean(false);

        TailscaleManager(JTextArea console, JLabel statusLabel, JLabel ipLabel) {
            this.console = console;
            this.statusLabel = statusLabel;
            this.ipLabel = ipLabel;
        }

        String getIp() { return ip; }
        List<TailscalePeer> getPeers() { return peers; }
        boolean isReady() { return !ip.isBlank(); }
        boolean wasServiceStartedByApp() { return serviceStartedByApp; }
        boolean wasConnectionStartedByApp() { return connectedByApp; }

        void inheritOwnership(boolean serviceOwned, boolean connectionOwned) {
            serviceStartedByApp = serviceOwned;
            connectedByApp = connectionOwned;
            inheritedOwnership = serviceOwned || connectionOwned;
            if (inheritedOwnership) logTo(console, "Tailscale ownership was transferred from the previous Nexxa instance during administrator restart.");
        }

        void startForApplication() {
            if (!started.compareAndSet(false, true)) return;
            Thread worker = new Thread(() -> {
                try {
                    cli = findCli();
                    if (cli == null) {
                        updateStatus("Tailscale not found", "Install the official Tailscale Windows client first.");
                        logTo(console, "Tailscale CLI was not found. Download: https://tailscale.com/download/windows");
                        return;
                    }
                    logTo(console, "Tailscale CLI: " + cli);

                    if (isWindows()) {
                        String serviceState = runCommand(List.of("sc.exe", "query", "Tailscale"), 8_000).output();
                        boolean running = serviceState.toUpperCase(Locale.ROOT).contains("RUNNING");
                        if (!running) {
                            logTo(console, "Starting the Tailscale Windows service because Nexxa needs it. Windows may show an administrator prompt.");
                            if (!runElevatedSc("start", "Tailscale")) {
                                updateStatus("Service not started", "Approve the Windows prompt or start Tailscale manually.");
                                logTo(console, "Could not start the Tailscale service. No network listener was opened.");
                                return;
                            }
                            serviceStartedByApp = true;
                            if (!waitForServiceState(true, 20_000)) {
                                updateStatus("Service start timeout", "Tailscale did not reach RUNNING state.");
                                logTo(console, "Tailscale service did not start within 20 seconds.");
                                return;
                            }
                            logTo(console, "Tailscale Windows service started by Nexxa.");
                        } else {
                            logTo(console, "Tailscale Windows service was already running; Nexxa will not stop a service it did not start.");
                        }
                        String startType = getServiceStartType();
                        if (!startType.isBlank()) {
                            logTo(console, "Tailscale Windows startup type: " + startType + ". Use 'Disable Tailscale auto-start' below to make it start only when Nexxa opens.");
                        }
                    }

                    String before = getTailscaleIp();
                    if (isTailscaleIpv4(before)) {
                        ip = before;
                        if (inheritedOwnership) {
                            logTo(console, "Tailscale remained connected across the administrator restart. Nexxa retained ownership and will apply the normal close policy.");
                        } else {
                            logTo(console, "Tailscale was already connected before Nexxa opened. Nexxa will leave that pre-existing connection untouched when it closes.");
                        }
                    } else {
                        logTo(console, "Connecting this PC to Tailscale...");
                        CommandResult up = runCommand(List.of(cli, "up"), 90_000);
                        if (!up.output().isBlank()) logMultiline("Tailscale", up.output());
                        if (up.exitCode() == 0) {
                            connectedByApp = true;
                            logTo(console, "Tailscale connection was initiated by Nexxa.");
                        } else {
                            logTo(console, "tailscale up returned exit code " + up.exitCode() + ". Sign-in may be required; follow any login URL in the console.");
                        }
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                        while (System.nanoTime() < deadline) {
                            String current = getTailscaleIp();
                            if (isTailscaleIpv4(current)) {
                                ip = current;
                                break;
                            }
                            Thread.sleep(1000);
                        }
                    }

                    refreshStatus(true);
                    scheduler.scheduleAtFixedRate(() -> refreshStatus(false), 5, 5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    updateStatus("Tailscale error", e.getMessage());
                    logTo(console, "Tailscale startup error: " + e.getMessage());
                }
            }, "Nexxa-Tailscale-Startup");
            worker.setDaemon(true);
            worker.start();
        }

        private String getTailscaleIp() {
            if (cli == null) return "";
            CommandResult result = runCommand(List.of(cli, "ip", "-4"), 8_000);
            if (result.exitCode() != 0) return "";
            return result.output().lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
        }

        private boolean waitForServiceState(boolean shouldRun, long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                String text = runCommand(List.of("sc.exe", "query", "Tailscale"), 5_000).output().toUpperCase(Locale.ROOT);
                boolean running = text.contains("RUNNING");
                if (running == shouldRun) return true;
                Thread.sleep(500);
            }
            return false;
        }

        private String getServiceStartType() {
            if (!isWindows()) return "";
            String output = runCommand(List.of("sc.exe", "qc", "Tailscale"), 8_000).output();
            for (String line : output.split("\\R")) {
                if (line.toUpperCase(Locale.ROOT).contains("START_TYPE")) return line.trim();
            }
            return "unknown";
        }

        private static boolean isTailscaleIpv4(String value) {
            if (value == null) return false;
            String[] parts = value.trim().split("\\.");
            if (parts.length != 4) return false;
            try {
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                int c = Integer.parseInt(parts[2]);
                int d = Integer.parseInt(parts[3]);
                return a == 100 && b >= 64 && b <= 127 && c >= 0 && c <= 255 && d >= 0 && d <= 255;
            } catch (NumberFormatException e) { return false; }
        }

        private void refreshStatus(boolean verbose) {
            try {
                String currentIp = getTailscaleIp();
                ip = isTailscaleIpv4(currentIp) ? currentIp : "";
                CommandResult statusResult = runCommand(List.of(cli, "status"), 10_000);
                String status = statusResult.output().trim();
                if (status.isBlank()) status = "No Tailscale status available";
                peers = parsePeers(status, ip);
                final String shownIp = ip.isBlank() ? "No Tailscale IPv4 yet" : "Tailscale IP: " + ip;
                final String shownStatus = ip.isBlank() ? "Tailscale: not connected" : "Tailscale: connected";
                SwingUtilities.invokeLater(() -> {
                    ipLabel.setText(shownIp);
                    statusLabel.setText(shownStatus);
                    statusLabel.setForeground(ip.isBlank() ? new Color(255, 205, 106) : GREEN);
                });
                if (verbose || !status.equals(lastStatus)) {
                    logTo(console, "Tailscale status: " + status.replace('\n', ' '));
                    lastStatus = status;
                }
            } catch (Exception e) {
                updateStatus("Tailscale unavailable", e.getMessage());
            }
        }

        private static List<TailscalePeer> parsePeers(String status, String selfIp) {
            List<TailscalePeer> found = new ArrayList<>();
            if (status == null) return List.of();
            for (String line : status.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 2 || !isTailscaleIpv4(parts[0])) continue;
                String peerIp = parts[0];
                String hostname = parts[1];
                String user = parts.length > 2 ? parts[2] : "";
                String os = parts.length > 3 ? parts[3] : "";
                boolean self = !selfIp.isBlank() && peerIp.equals(selfIp);
                String lower = trimmed.toLowerCase(Locale.ROOT);
                String state = lower.contains("offline") ? "offline" : (self ? "this device" : "online");
                found.add(new TailscalePeer(peerIp, hostname, user, os, state, self));
            }
            found.sort(Comparator.comparing((TailscalePeer peer) -> !peer.self())
                    .thenComparing(TailscalePeer::hostname, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(found);
        }

        private void updateStatus(String status, String detail) {
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Tailscale: " + status);
                statusLabel.setForeground(new Color(255, 205, 106));
                ipLabel.setText(detail == null ? "" : detail);
            });
        }

        void configureManualStartup() {
            Thread t = new Thread(() -> {
                if (!isWindows()) {
                    logTo(console, "Changing the Windows service startup type is available only on Windows.");
                    return;
                }
                logTo(console, "Requesting administrator permission to change Tailscale to Manual startup. This prevents its Windows service from starting automatically at boot.");
                boolean ok = runElevatedSc("config", "Tailscale", "start=", "demand");
                if (ok) {
                    // The user explicitly opted into app-managed lifecycle. On this first run,
                    // the service may already have been auto-started by Windows; stop it on exit.
                    stopServiceOnExitRequested = true;
                    logTo(console, "Tailscale service startup is now Manual. Nexxa will start it when the app opens and stop it on exit.");
                } else {
                    logTo(console, "Could not change Tailscale startup. Approve the UAC prompt or use Windows Services to set Tailscale startup type to Manual.");
                }
            }, "Nexxa-Tailscale-Configure");
            t.setDaemon(true);
            t.start();
        }

        void stopForApplication(boolean stopWhenClosing) {
            scheduler.shutdownNow();
            if (!stopWhenClosing) {
                logTo(console, "Tailscale was left running because 'keep running after Nexxa closes' is enabled.");
                return;
            }
            boolean shouldDisconnect = connectedByApp || serviceStartedByApp || stopServiceOnExitRequested;
            if (shouldDisconnect && cli != null) {
                logTo(console, "Disconnecting Tailscale because Nexxa is closing...");
                CommandResult down = runCommand(List.of(cli, "down"), 15_000);
                if (!down.output().isBlank()) logMultiline("Tailscale", down.output());
                if (down.exitCode() != 0) logTo(console, "tailscale down returned exit code " + down.exitCode() + ".");
            } else {
                logTo(console, "Tailscale was already active before Nexxa opened; its existing connection was left unchanged.");
            }
            if ((serviceStartedByApp || stopServiceOnExitRequested) && isWindows()) {
                logTo(console, "Stopping the Tailscale Windows service that Nexxa started. Windows may show an administrator prompt.");
                boolean stopped = runElevatedSc("stop", "Tailscale");
                logTo(console, stopped ? "Tailscale Windows service stopped." : "Could not stop the service. It may remain running until stopped manually.");
            }
            ip = "";
        }

        void allowNexxaFirewall(int port) {
            Thread t = new Thread(() -> {
                if (!isWindows()) {
                    logTo(console, "Windows Firewall integration is Windows-only.");
                    return;
                }
                logTo(console, "Requesting administrator permission to allow TCP " + port + " only from Tailscale IPv4 peers (100.64.0.0/10)...");
                String arguments = "advfirewall firewall add rule name=NexxaRemote-Tailscale dir=in action=allow protocol=TCP localport=" + port + " remoteip=100.64.0.0/10";
                String ps = "$p=Start-Process -FilePath 'netsh.exe' -ArgumentList '" + arguments + "' -Verb RunAs -Wait -PassThru; exit $p.ExitCode";
                CommandResult result = runCommand(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", ps), 60_000);
                logTo(console, result.exitCode() == 0 ? "Windows Firewall rule added for Tailscale peers on TCP " + port + "." : "Firewall rule was not added. " + result.output().trim());
            }, "Nexxa-Firewall-Setup");
            t.setDaemon(true);
            t.start();
        }

        private String findCli() {
            if (!isWindows()) {
                CommandResult where = runCommand(List.of("sh", "-lc", "command -v tailscale"), 5_000);
                String found = where.output().trim();
                return found.isBlank() ? null : found;
            }
            List<String> candidates = new ArrayList<>();
            String programFiles = System.getenv("ProgramFiles");
            String localAppData = System.getenv("LOCALAPPDATA");
            if (programFiles != null) {
                candidates.add(programFiles + "\\Tailscale\\tailscale.exe");
                candidates.add(programFiles + "\\Tailscale IPN\\tailscale.exe");
            }
            if (localAppData != null) candidates.add(localAppData + "\\Tailscale\\tailscale.exe");
            candidates.add("C:\\Program Files\\Tailscale\\tailscale.exe");
            candidates.add("C:\\Program Files\\Tailscale IPN\\tailscale.exe");
            for (String candidate : candidates) if (Files.isRegularFile(Path.of(candidate))) return candidate;
            CommandResult where = runCommand(List.of("where.exe", "tailscale.exe"), 5_000);
            String found = where.output().lines().findFirst().orElse("").trim();
            return found.isBlank() ? null : found;
        }

        private boolean runElevatedSc(String... args) {
            try {
                String joined = String.join(",", Arrays.stream(args)
                        .map(value -> "'" + value.replace("'", "''") + "'").toList());
                String ps = "$p=Start-Process -FilePath 'sc.exe' -ArgumentList @(" + joined + ") -Verb RunAs -Wait -PassThru; exit $p.ExitCode";
                CommandResult result = runCommand(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-Command", ps), 60_000);
                return result.exitCode() == 0;
            } catch (Exception e) {
                logTo(console, "Administrator service action failed: " + e.getMessage());
                return false;
            }
        }

        private void logMultiline(String prefix, String text) {
            for (String line : text.split("\\R")) if (!line.isBlank()) logTo(console, prefix + ": " + line.trim());
        }

        private record CommandResult(int exitCode, String output) { }

        private static CommandResult runCommand(List<String> command, long timeoutMs) {
            Process process = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                process = builder.start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                Process runningProcess = process;
                Thread reader = new Thread(() -> {
                    try (InputStream in = runningProcess.getInputStream()) { in.transferTo(output); }
                    catch (IOException ignored) { }
                }, "Nexxa-Command-Output");
                reader.setDaemon(true);
                reader.start();
                boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new CommandResult(124, "Command timed out: " + String.join(" ", command));
                }
                reader.join(1500);
                return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                if (process != null) process.destroyForcibly();
                return new CommandResult(1, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
    }

    @SuppressWarnings("serial")
    private static final class ScrollablePanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;
        ScrollablePanel(LayoutManager layout) { super(layout); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(40, (orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width) - 24);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static final class HostPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final JTextArea console;
        private final transient TailscaleManager tailscale;
        private final transient MainWindow window;
        private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 7);
        private final JTextField ipField = new JTextField("Waiting for Tailscale...", 28);
        private final JTextField fingerprintField = new JTextField("Creating identity...");
        private final JTextField accessKeyField = new JTextField();
        private final JTextField inviteField = new JTextField();
        private final JCheckBox allowControl = new JCheckBox("Allow remote keyboard and mouse control", restoreRemoteControlPermission);
        private final JCheckBox prioritizeHostInput = new JCheckBox("Prioritize this PC's physical keyboard and mouse", true);
        private final JCheckBox allowClipboard = new JCheckBox("Synchronize plain-text clipboard");
        private final JCheckBox shareAudio = new JCheckBox("Share desktop audio (experimental)");
        private final JCheckBox stopTailscaleOnExit = new JCheckBox("Stop Tailscale when Nexxa closes (only if Nexxa started it)", true);
        private final JButton checkAudioDevices = new JButton("Check audio devices");
        private final JComboBox<StreamProfile> profile = new JComboBox<>(new StreamProfile[]{
                StreamProfile.fast(), StreamProfile.balanced(), StreamProfile.quality(), StreamProfile.ultra()
        });
        private final JButton startButton = new JButton("Start sharing");
        private final JButton stopButton = new JButton("Stop sharing");
        private final JButton copyInvite = new JButton("Copy invite link");
        private final JButton copyKey = new JButton("Copy access key");
        private final JButton copyFingerprint = new JButton("Copy SHA-256");
        private final JButton refreshDevices = new JButton("Refresh devices");
        private final JButton copyPeerIp = new JButton("Copy selected IP");
        private final JButton shareAccount = new JButton("Share with another Tailscale account");
        private final JButton manualStartup = new JButton("Disable Windows auto-start");
        private final JButton firewall = new JButton("Allow Nexxa through firewall");
        private final JButton enableAdministrator = new JButton("Enable administrator access (UAC restart)");
        private final JLabel administratorStatus = new JLabel();
        private final JLabel status = new JLabel("Not sharing");
        private final JLabel connectedViewer = new JLabel("No active Nexxa viewer");
        private final DefaultListModel<TailscalePeer> deviceModel = new DefaultListModel<>();
        private JScrollPane mainScroll;
        private JScrollPane settingsScroll;
        private JTabbedPane hostContentTabs;
        private JSplitPane hostSplit;
        private final JList<TailscalePeer> devices = new JList<>(deviceModel);
        private final AtomicBoolean controlAllowed = new AtomicBoolean(restoreRemoteControlPermission);
        private final AtomicBoolean hostPriority = new AtomicBoolean(true);
        private final AtomicBoolean clipboardAllowed = new AtomicBoolean(false);
        private final AtomicBoolean audioAllowed = new AtomicBoolean(false);
        private volatile transient IdentityStore identity;
        private volatile transient HostServer server;
        private volatile String activeAccessKey = "";
        private final javax.swing.Timer peerTimer;

        HostPanel(JTextArea console, TailscaleManager tailscale, MainWindow window) {
            this.console = console; this.tailscale = tailscale; this.window = window;
            setLayout(new BorderLayout(12, 12)); setBackground(BG); setBorder(new EmptyBorder(12, 12, 12, 12));
            styleInput(portField); styleInput(ipField); styleInput(fingerprintField); styleInput(accessKeyField); styleInput(inviteField);
            ipField.setEditable(false); fingerprintField.setEditable(false); accessKeyField.setEditable(false); inviteField.setEditable(false);
            styleButton(startButton, true); styleButton(stopButton, false); styleButton(copyInvite, false);
            styleButton(copyKey, false); styleButton(copyFingerprint, false); styleButton(checkAudioDevices, false);
            styleButton(refreshDevices, false); styleButton(copyPeerIp, false); styleButton(shareAccount, false);
            styleButton(manualStartup, false); styleButton(firewall, false); styleButton(enableAdministrator, false);
            styleCheckBox(allowControl); styleCheckBox(prioritizeHostInput); styleCheckBox(allowClipboard);
            styleCheckBox(shareAudio); styleCheckBox(stopTailscaleOnExit);
            profile.setUI(new javax.swing.plaf.basic.BasicComboBoxUI());
            profile.setBackground(PANEL_ALT); profile.setForeground(TEXT); profile.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            profile.setBorder(BorderFactory.createLineBorder(new Color(59, 77, 103)));

            JPanel upper = new JPanel(new GridLayout(1, 2, 12, 0)); upper.setOpaque(false);
            upper.add(buildShareCard());
            upper.add(buildDevicesCard());

            manualStartup.addActionListener(e -> {
                if (confirmTailscaleManualStartup()) tailscale.configureManualStartup();
            });
            firewall.addActionListener(e -> {
                try { int port = Integer.parseInt(portField.getText().trim()); if (port < 1 || port > 65535) throw new NumberFormatException(); tailscale.allowNexxaFirewall(port); }
                catch (NumberFormatException ex) { showStyledMessage(this, "Enter a valid TCP port first.", "Invalid port", false); }
            });
            shareAccount.addActionListener(e -> MainWindow.openUrl(TAILSCALE_ADMIN_MACHINES));
            enableAdministrator.addActionListener(e -> window.enableAdministratorMode());
            if (runningAsAdministrator) {
                administratorStatus.setText("Administrator mode is ACTIVE");
                administratorStatus.setForeground(new Color(255, 190, 105));
                enableAdministrator.setText("Administrator access enabled");
                enableAdministrator.setEnabled(false);
            } else {
                administratorStatus.setText(isWindows() ? "Standard Windows permissions" : "Windows administrator mode unavailable on this platform");
                administratorStatus.setForeground(MUTED);
                enableAdministrator.setEnabled(isWindows());
            }
            administratorStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));

            JPanel settingsGrid = new JPanel(new GridLayout(2, 2, 10, 10));
            settingsGrid.setOpaque(false);
            settingsGrid.add(settingTile("Tailscale startup", "Prevent the Tailscale Windows service from starting automatically at boot.", manualStartup));
            settingsGrid.add(settingTile("Windows Firewall", "Allow Nexxa's host port from Tailscale peers only.", firewall));
            settingsGrid.add(settingTile("Cross-account access", "Open Tailscale Admin to share this computer with another account.", shareAccount));
            settingsGrid.add(settingTile("When Nexxa closes", "Stop Tailscale on exit only when Nexxa started it.", stopTailscaleOnExit));
            JPanel settings = cardPanel("Windows & Tailscale settings", "Manage startup, firewall access, cross-account sharing and shutdown behavior here.");
            settings.add(settingsGrid, BorderLayout.CENTER);

            // Keep the main share workspace uncluttered. Windows/Tailscale actions live in
            // a dedicated host-only tab instead of a FlowLayout row below the large form.
            JPanel shareArea = new ScrollablePanel(new BorderLayout());
            shareArea.setOpaque(false);
            shareArea.add(upper, BorderLayout.CENTER);

            mainScroll = new JScrollPane(shareArea, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            styleScrollPane(mainScroll);
            mainScroll.getVerticalScrollBar().setUnitIncrement(18);

            settingsScroll = new JScrollPane(settings, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            styleScrollPane(settingsScroll);
            settingsScroll.getVerticalScrollBar().setUnitIncrement(18);
            settingsScroll.getViewport().setBackground(BG);

            hostContentTabs = new JTabbedPane();
            hostContentTabs.addTab("Share & devices", mainScroll);
            hostContentTabs.addTab("Windows & Tailscale", settingsScroll);
            hostContentTabs.setBackground(BG);
            hostContentTabs.setForeground(TEXT);

            JPanel consolePanel = new JPanel(new BorderLayout(6, 6));
            consolePanel.setBackground(PANEL); consolePanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(48, 65, 89)), new EmptyBorder(8, 10, 8, 10)));
            JPanel consoleHeader = new JPanel(new BorderLayout()); consoleHeader.setOpaque(false);
            JLabel consoleTitle = new JLabel("ACTIVITY CONSOLE"); consoleTitle.setForeground(TEXT);
            consoleTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
            JPanel consoleButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); consoleButtons.setOpaque(false);
            JButton copyLog = new JButton("Copy log"), clearLog = new JButton("Clear");
            styleButton(copyLog, false); styleButton(clearLog, false);
            copyLog.addActionListener(e -> setLocalClipboardText(console.getText()));
            clearLog.addActionListener(e -> console.setText("") );
            consoleButtons.add(copyLog); consoleButtons.add(clearLog);
            consoleHeader.add(consoleTitle, BorderLayout.WEST); consoleHeader.add(consoleButtons, BorderLayout.EAST);
            JScrollPane consoleScroll = new JScrollPane(console);
            styleScrollPane(consoleScroll);
            consoleScroll.setBorder(BorderFactory.createLineBorder(new Color(42, 57, 77)));
            consoleScroll.getViewport().setBackground(new Color(10, 15, 24));
            consolePanel.add(consoleHeader, BorderLayout.NORTH); consolePanel.add(consoleScroll, BorderLayout.CENTER);
            consolePanel.setPreferredSize(new Dimension(100, 138));

            hostSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, hostContentTabs, consolePanel);
            hostSplit.setResizeWeight(0.83); hostSplit.setBorder(null); hostSplit.setOpaque(false); hostSplit.setDividerSize(7);
            add(hostSplit, BorderLayout.CENTER);

            startButton.addActionListener(e -> startHost()); stopButton.addActionListener(e -> stopHost());
            copyInvite.addActionListener(e -> copyField(inviteField, "Invitation link copied."));
            copyKey.addActionListener(e -> copyField(accessKeyField, "Temporary access key copied."));
            copyFingerprint.addActionListener(e -> copyField(fingerprintField, "SHA-256 fingerprint copied."));
            checkAudioDevices.addActionListener(e -> logTo(console, AudioLine.describeCaptureDevices()));
            allowControl.addActionListener(e -> {
                controlAllowed.set(allowControl.isSelected());
                HostServer active = server;
                if (active != null) {
                    if (!controlAllowed.get()) active.releaseHeldInputs();
                    active.publishRemoteControlAvailability(controlAllowed.get());
                }
                logTo(console, "Remote keyboard/mouse control " + (controlAllowed.get() ? "enabled" : "paused by host") + ".");
            });
            prioritizeHostInput.addActionListener(e -> {
                hostPriority.set(prioritizeHostInput.isSelected());
                HostServer active = server;
                if (active != null) active.updateHostPriorityMode();
                logTo(console, "Host physical input priority " + (hostPriority.get() ? "enabled" : "disabled") + ".");
            });
            allowClipboard.addActionListener(e -> { clipboardAllowed.set(allowClipboard.isSelected()); logTo(console, "Clipboard synchronization " + (clipboardAllowed.get() ? "enabled" : "disabled") + "."); });
            shareAudio.addActionListener(e -> { audioAllowed.set(shareAudio.isSelected()); logTo(console, "Desktop audio sharing " + (audioAllowed.get() ? "requested" : "disabled") + "."); });
            refreshDevices.addActionListener(e -> refreshPeerList());
            copyPeerIp.addActionListener(e -> {
                TailscalePeer selected = devices.getSelectedValue();
                if (selected != null) { setLocalClipboardText(selected.ip()); logTo(console, "Copied Tailscale IP for " + selected.hostname() + "."); }
            });
            stopButton.setEnabled(false); copyInvite.setEnabled(false); copyKey.setEnabled(false); copyFingerprint.setEnabled(false);
            devices.setBackground(PANEL_ALT); devices.setForeground(TEXT); devices.setSelectionBackground(new Color(49, 81, 120));
            devices.setSelectionForeground(Color.WHITE); devices.setFont(new Font("Consolas", Font.PLAIN, 12));
            devices.setFixedCellHeight(42);
            devices.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                    label.setOpaque(true); label.setBackground(selected ? new Color(49, 81, 120) : PANEL_ALT); label.setForeground(TEXT);
                    label.setBorder(new EmptyBorder(7, 9, 7, 9));
                    if (value instanceof TailscalePeer peer) {
                        label.setText("●  " + peer.hostname() + "     " + peer.ip() + (peer.self() ? "   • THIS PC" : "   • " + peer.state().toUpperCase(Locale.ROOT)));
                        label.setForeground(peer.self() ? new Color(133, 205, 255) : ("offline".equals(peer.state()) ? MUTED : TEXT));
                    }
                    return label;
                }
            });
            devices.addListSelectionListener(e -> copyPeerIp.setEnabled(devices.getSelectedValue() != null));
            peerTimer = new javax.swing.Timer(3500, e -> refreshPeerList()); peerTimer.start();
            refreshPeerList();
            logTo(console, "Host page ready. Connected Tailscale devices will appear in the device list.");
            if (restoreRemoteControlPermission) {
                logTo(console, "The host's previously enabled remote keyboard/mouse permission was preserved across the administrator restart.");
                logTo(console, "IMPORTANT: Start sharing again and send the NEW invitation to the viewer. The previous session was disconnected during UAC elevation.");
            }
        }

        private boolean confirmTailscaleManualStartup() {
            JDialog dialog = new JDialog(window, APP_NAME + " — Tailscale startup", Dialog.ModalityType.APPLICATION_MODAL);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setResizable(false);
            JPanel root = new JPanel(new BorderLayout(0, 14));
            root.setBackground(BG);
            root.setBorder(new EmptyBorder(20, 22, 18, 22));
            JLabel heading = new JLabel("Disable Tailscale Windows auto-start?");
            heading.setForeground(TEXT);
            heading.setFont(new Font("Segoe UI", Font.BOLD, 17));
            root.add(heading, BorderLayout.NORTH);
            JTextArea body = new JTextArea("This changes the Tailscale Windows service startup type to Manual. Tailscale will no longer start automatically at Windows boot. Nexxa can start it when you open the app. Windows will ask for administrator approval.");
            body.setEditable(false); body.setFocusable(false); body.setLineWrap(true); body.setWrapStyleWord(true);
            body.setRows(4); body.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            body.setForeground(TEXT); body.setBackground(PANEL); body.setBorder(new EmptyBorder(13, 14, 13, 14));
            JPanel bodyCard = new JPanel(new BorderLayout()); bodyCard.setBackground(PANEL);
            bodyCard.setBorder(BorderFactory.createLineBorder(new Color(48, 65, 89))); bodyCard.add(body);
            root.add(bodyCard, BorderLayout.CENTER);
            boolean[] confirmed = {false};
            JButton cancel = new JButton("Cancel"), confirm = new JButton("Disable auto-start");
            styleButton(cancel, false); styleButton(confirm, true);
            cancel.setPreferredSize(new Dimension(130, 42)); confirm.setPreferredSize(new Dimension(190, 42));
            cancel.addActionListener(e -> dialog.dispose());
            confirm.addActionListener(e -> { confirmed[0] = true; dialog.dispose(); });
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 9, 0)); actions.setOpaque(false);
            actions.add(cancel); actions.add(confirm); root.add(actions, BorderLayout.SOUTH);
            dialog.setContentPane(root); dialog.getRootPane().setDefaultButton(cancel);
            dialog.getRootPane().setBorder(BorderFactory.createLineBorder(new Color(63, 83, 111)));
            dialog.setSize(620, 300); dialog.setMinimumSize(new Dimension(620, 300)); dialog.setLocationRelativeTo(this);
            Rectangle bounds = dialog.getGraphicsConfiguration().getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());
            int maxW = Math.max(520, bounds.width - insets.left - insets.right - 24);
            int maxH = Math.max(260, bounds.height - insets.top - insets.bottom - 24);
            dialog.setSize(Math.min(dialog.getWidth(), maxW), Math.min(dialog.getHeight(), maxH));
            dialog.setVisible(true);
            return confirmed[0];
        }

        private static JPanel settingTile(String title, String description, JComponent action) {
            JPanel tile = new JPanel(new BorderLayout(7, 8));
            tile.setBackground(PANEL_ALT);
            tile.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(48, 65, 89)), new EmptyBorder(11, 12, 11, 12)));
            JPanel labels = new JPanel(new GridLayout(0, 1, 2, 3)); labels.setOpaque(false);
            JLabel heading = new JLabel(title); heading.setForeground(TEXT);
            heading.setFont(new Font("Segoe UI", Font.BOLD, 12));
            JTextArea detail = new JTextArea(description);
            detail.setEditable(false); detail.setFocusable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true);
            detail.setRows(2); detail.setOpaque(false); detail.setForeground(MUTED);
            detail.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            detail.setBorder(new EmptyBorder(0, 0, 0, 0));
            labels.add(heading); labels.add(detail);
            tile.add(labels, BorderLayout.CENTER);
            JPanel actionRow = new JPanel(new BorderLayout()); actionRow.setOpaque(false);
            if (action instanceof JButton button) {
                button.setPreferredSize(new Dimension(240, 40));
                actionRow.add(button, BorderLayout.CENTER);
            } else {
                actionRow.add(action, BorderLayout.CENTER);
            }
            tile.add(actionRow, BorderLayout.SOUTH);
            tile.setMinimumSize(new Dimension(280, 112));
            return tile;
        }

        private static void showStyledMessage(Component owner, String message, String title, boolean warning) {
            JPanel content = new JPanel(new BorderLayout(0, 8)); content.setBackground(PANEL);
            content.setBorder(new EmptyBorder(12, 14, 12, 14));
            JTextArea text = new JTextArea(message); text.setEditable(false); text.setFocusable(false);
            text.setLineWrap(true); text.setWrapStyleWord(true); text.setRows(3);
            text.setForeground(TEXT); text.setBackground(PANEL); text.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            content.add(text, BorderLayout.CENTER);
            JOptionPane pane = new JOptionPane(content, warning ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE,
                    JOptionPane.DEFAULT_OPTION, null, new Object[]{"OK"}, "OK");
            JDialog dialog = pane.createDialog(owner, title); dialog.setModal(true); dialog.setVisible(true);
        }

        private JPanel buildShareCard() {
            JPanel card = cardPanel("Share this computer", "Create a secure invitation and control exactly what the viewer can do.");
            JPanel form = new JPanel(new GridBagLayout()); form.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints(); c.insets = new Insets(5, 5, 5, 5); c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.WEST;
            int row = 0;
            addRow(form, c, row++, "Tailscale address", ipField);
            addRow(form, c, row++, "Host TCP port", portField);
            addRow(form, c, row++, "Certificate SHA-256", fingerprintField);
            addRow(form, c, row++, "Temporary access key", accessKeyField);
            addRow(form, c, row++, "Invitation URL", inviteField);
            c.gridx = 1; c.gridy = row++; form.add(allowControl, c);
            c.gridy = row++; form.add(prioritizeHostInput, c);
            c.gridy = row++; form.add(allowClipboard, c);
            c.gridy = row++; form.add(shareAudio, c);
            JPanel adminRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); adminRow.setOpaque(false);
            adminRow.add(administratorStatus); adminRow.add(enableAdministrator);
            addRow(form, c, row++, "Windows privileges", adminRow);
            JPanel profileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); profileRow.setOpaque(false);
            JLabel profileLabel = new JLabel("Streaming quality"); profileLabel.setForeground(MUTED); profileRow.add(profileLabel); profileRow.add(profile); profileRow.add(checkAudioDevices);
            c.gridy = row++; form.add(profileRow, c);
            JPanel buttons = new JPanel(new GridLayout(2, 3, 6, 6)); buttons.setOpaque(false);
            buttons.add(startButton); buttons.add(stopButton); buttons.add(copyInvite); buttons.add(copyKey); buttons.add(copyFingerprint); buttons.add(new JLabel(""));
            c.gridy = row++; form.add(buttons, c);
            status.setForeground(GREEN); status.setFont(new Font("Segoe UI", Font.BOLD, 12));
            connectedViewer.setForeground(MUTED); connectedViewer.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            JPanel state = new JPanel(new GridLayout(0, 1, 2, 3)); state.setOpaque(false); state.add(status); state.add(connectedViewer);
            c.gridy = row; form.add(state, c);
            card.add(form, BorderLayout.CENTER);
            JTextArea note = new JTextArea("The invitation is a secret: anyone holding it may attempt to connect until you stop sharing. For a different Tailscale account, share this device in Tailscale Admin first and have the other user accept. The host's physical keyboard and mouse take priority when used. Administrator mode requires a local Windows UAC approval and restarts Nexxa; a trusted viewer with remote control enabled can then operate applications accessible to the elevated Nexxa process. Secure-desktop UAC prompts still require local approval. Stop sharing to revoke the invitation.");
            note.setLineWrap(true); note.setWrapStyleWord(true); note.setEditable(false); note.setFocusable(false);
            note.setBackground(PANEL_ALT); note.setForeground(MUTED); note.setFont(new Font("Segoe UI", Font.PLAIN, 11)); note.setBorder(new EmptyBorder(9, 10, 9, 10));
            card.add(note, BorderLayout.SOUTH);
            return card;
        }

        private JPanel buildDevicesCard() {
            JPanel card = cardPanel("Devices on your Tailscale network", "Online and offline devices visible to this Tailscale account or shared with it.");
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0)); actions.setOpaque(false);
            actions.add(refreshDevices); actions.add(copyPeerIp); actions.add(shareAccount);
            copyPeerIp.setEnabled(false);
            JScrollPane listScroll = new JScrollPane(devices);
            styleScrollPane(listScroll);
            listScroll.setBorder(BorderFactory.createLineBorder(new Color(48, 65, 89)));
            listScroll.getViewport().setBackground(PANEL_ALT);
            JLabel hint = new JLabel("Device names and 100.x Tailscale IPs are read from `tailscale status`.");
            hint.setForeground(MUTED); hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            JPanel body = new JPanel(new BorderLayout(8, 8)); body.setOpaque(false);
            body.add(actions, BorderLayout.NORTH); body.add(listScroll, BorderLayout.CENTER); body.add(hint, BorderLayout.SOUTH);
            card.add(body, BorderLayout.CENTER);
            return card;
        }

        private static void addRow(JPanel form, GridBagConstraints c, int row, String label, Component component) {
            JLabel text = new JLabel(label + ":"); text.setForeground(MUTED); text.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            c.gridx = 0; c.gridy = row; c.weightx = 0; form.add(text, c);
            c.gridx = 1; c.gridy = row; c.weightx = 1; form.add(component, c);
        }

        private void copyField(JTextField field, String message) {
            if (!field.getText().isBlank()) { setLocalClipboardText(field.getText()); logTo(console, message); }
        }

        private void refreshPeerList() {
            List<TailscalePeer> peers = tailscale.getPeers();
            if (peers.isEmpty()) {
                if (deviceModel.isEmpty()) deviceModel.addElement(new TailscalePeer("—", "Waiting for Tailscale status", "", "", "unknown", false));
                return;
            }
            deviceModel.clear();
            for (TailscalePeer peer : peers) deviceModel.addElement(peer);
        }

        boolean shouldStopTailscaleOnExit() { return stopTailscaleOnExit.isSelected(); }
        boolean isRemoteControlAllowed() { return allowControl.isSelected() && controlAllowed.get(); }
        void setAdministratorActionEnabled(boolean enabled) { enableAdministrator.setEnabled(enabled && !runningAsAdministrator && isWindows()); }
        void resetScroll() {
            SwingUtilities.invokeLater(() -> {
                if (hostSplit != null) hostSplit.setDividerLocation(0.83);
                if (mainScroll != null) {
                    mainScroll.getVerticalScrollBar().setValue(0);
                    mainScroll.getHorizontalScrollBar().setValue(0);
                    mainScroll.getViewport().setViewPosition(new Point(0, 0));
                }
            });
        }

        private static void addRowSpacer(GridBagConstraints c) { c.gridx = 0; c.weightx = 0; }

        void setIdentity(IdentityStore identity) {
            this.identity = identity;
            fingerprintField.setText(identity.fingerprint);
            fingerprintField.setCaretPosition(0);
            logTo(console, "Host identity loaded. Private key stays on this PC: " + identity.keyStoreFile.getParent());
        }

        private void startHost() {
            if (server != null) return;
            IdentityStore currentIdentity = identity;
            if (currentIdentity == null) {
                JOptionPane.showMessageDialog(this, "The host identity is still being created. Wait a moment and try again.", APP_NAME, JOptionPane.INFORMATION_MESSAGE); return;
            }
            String tailIp = tailscale.getIp();
            if (tailIp == null || tailIp.isBlank()) {
                JOptionPane.showMessageDialog(this, "Tailscale is not connected yet. Wait for the connected status in the title area.", APP_NAME, JOptionPane.WARNING_MESSAGE); return;
            }
            final int requestedPort;
            try {
                requestedPort = Integer.parseInt(portField.getText().trim());
                if (requestedPort < 1 || requestedPort > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
            } catch (Exception e) { JOptionPane.showMessageDialog(this, e.getMessage(), APP_NAME, JOptionPane.WARNING_MESSAGE); return; }
            String accessKey = randomToken(32);
            StreamProfile selected = (StreamProfile) profile.getSelectedItem();
            if (selected == null) selected = StreamProfile.fast();
            HostServer created = null;
            int actualPort = requestedPort;
            try {
                try {
                    created = new HostServer(currentIdentity.serverContext, tailIp, actualPort, accessKey,
                            controlAllowed, hostPriority, clipboardAllowed, audioAllowed, selected, console, connectedViewer);
                    created.start();
                } catch (BindException bind) {
                    if (created != null) { created.stop(); created = null; }
                    if (requestedPort != DEFAULT_PORT) throw bind;
                    boolean started = false;
                    for (int candidate = DEFAULT_PORT + 1; candidate <= DEFAULT_PORT + 15; candidate++) {
                        try {
                            created = new HostServer(currentIdentity.serverContext, tailIp, candidate, accessKey,
                                    controlAllowed, hostPriority, clipboardAllowed, audioAllowed, selected, console, connectedViewer);
                            created.start(); actualPort = candidate; started = true; break;
                        } catch (BindException ignored) { if (created != null) created.stop(); created = null; }
                    }
                    if (!started) throw new BindException("TCP ports 24800–24815 are already in use. Close the conflicting listener or choose another port.");
                    logTo(console, "Port " + requestedPort + " was busy. Nexxa selected available port " + actualPort + ".");
                    portField.setText(String.valueOf(actualPort));
                }
                server = created;
                activeAccessKey = accessKey;
                accessKeyField.setText(accessKey);
                inviteField.setText(createInvite(tailIp, actualPort, currentIdentity.fingerprint, accessKey));
                inviteField.setCaretPosition(0);
                fingerprintField.setCaretPosition(0);
                ipField.setText(tailIp + ":" + actualPort);
                startButton.setEnabled(false); stopButton.setEnabled(true); copyInvite.setEnabled(true); copyKey.setEnabled(true); copyFingerprint.setEnabled(true);
                status.setText("Sharing • waiting for viewer");
                logTo(console, "Host server listening on Tailscale address " + tailIp + ":" + actualPort + ".");
                logTo(console, "Invitation created. Share the full nexxa:// link only with the intended viewer.");
                logTo(console, "Remote input request is " + (controlAllowed.get() ? "enabled" : "disabled") + "; host input priority is " + (hostPriority.get() ? "enabled" : "disabled") + ".");
            } catch (Exception e) {
                if (created != null) created.stop();
                server = null;
                logTo(console, "Could not start host listener: " + e.getMessage());
                String detail = e instanceof BindException ? "The selected Tailscale address/port is already occupied. Nexxa tried nearby ports.\n" : "";
                JOptionPane.showMessageDialog(this, "Could not start sharing.\n" + detail + e.getMessage(), APP_NAME, JOptionPane.ERROR_MESSAGE);
            }
        }

        void stopHost() {
            HostServer active = server;
            if (active != null) {
                active.stop(); server = null;
                logTo(console, "Host sharing stopped. The old invitation key is no longer valid.");
            }
            activeAccessKey = ""; accessKeyField.setText(""); inviteField.setText("");
            startButton.setEnabled(true); stopButton.setEnabled(false); copyInvite.setEnabled(false); copyKey.setEnabled(false); copyFingerprint.setEnabled(identity != null);
            status.setText("Not sharing"); connectedViewer.setText("No active Nexxa viewer");
        }
    }

    /**
     * Windows low-level hooks distinguish physical host activity from injected SendInput events.
     * This keeps remote cursor/key traffic from fighting the person at the host computer.
     * PowerShell and the .NET Framework compiler ship with supported Windows installations;
     * no additional Java dependency or native DLL is required.
     */
    private static final class LocalInputMonitor implements AutoCloseable {
        // Host physical input wins while a key/button is held and for a short grace period
        // after release/movement. Injected Java Robot events are ignored by the Win32 hooks.
        private static final long PRIORITY_WINDOW_MS = 450;
        private final Process process;
        private final CountDownLatch readyLatch = new CountDownLatch(1);
        private final AtomicLong lastPhysicalInputNanos = new AtomicLong(System.nanoTime() - TimeUnit.SECONDS.toNanos(10));
        private final AtomicBoolean physicalInputHeld = new AtomicBoolean(false);
        private final AtomicBoolean healthy = new AtomicBoolean(false);
        private final AtomicReference<String> startupError = new AtomicReference<>("");
        private final StringBuilder diagnostics = new StringBuilder();

        private LocalInputMonitor(Process process, JTextArea console) {
            this.process = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        if (line.equals("READY")) {
                            healthy.set(true);
                            readyLatch.countDown();
                        } else if (line.startsWith("STATE:")) {
                            // STATE:<idle milliseconds>:<1 when a physical key/button is held>
                            String[] parts = line.split(":");
                            if (parts.length == 3) {
                                try {
                                    long idleMs = Math.max(0, Long.parseLong(parts[1]));
                                    physicalInputHeld.set("1".equals(parts[2]));
                                    lastPhysicalInputNanos.set(System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(idleMs));
                                } catch (NumberFormatException ignored) { }
                            }
                        } else if (line.startsWith("ERROR:")) {
                            startupError.set(line.substring(6).trim());
                            readyLatch.countDown();
                            appendDiagnostic(line);
                        } else if (!line.isBlank()) {
                            appendDiagnostic(line);
                        }
                    }
                } catch (IOException e) {
                    appendDiagnostic("Reader error: " + e.getMessage());
                } finally {
                    healthy.set(false);
                    readyLatch.countDown();
                    if (process.isAlive()) logTo(console, "Host physical-input monitor stopped unexpectedly.");
                }
            }, "Nexxa-Physical-Input-Reader");
            reader.setDaemon(true);
            reader.start();
        }

        private void appendDiagnostic(String line) {
            synchronized (diagnostics) {
                if (diagnostics.length() < 6000) diagnostics.append(line).append(System.lineSeparator());
            }
        }

        private String diagnosticsText() {
            synchronized (diagnostics) { return diagnostics.toString().trim(); }
        }

        static LocalInputMonitor start(JTextArea console) throws Exception {
            if (!isWindows()) throw new IOException("Automatic host-input priority is supported on Windows only.");
            String source = """
$ErrorActionPreference = 'Stop'
$src = @'
using System;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Threading;
public static class NexxaPhysicalInputHooks {
    private delegate IntPtr HookProc(int code, IntPtr wParam, IntPtr lParam);
    [StructLayout(LayoutKind.Sequential)] private struct KBDLLHOOKSTRUCT { public uint vkCode, scanCode, flags, time; public IntPtr extra; }
    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int x, y; }
    [StructLayout(LayoutKind.Sequential)] private struct MSLLHOOKSTRUCT { public POINT pt; public uint mouseData, flags, time; public IntPtr extra; }
    [StructLayout(LayoutKind.Sequential)] private struct MSG { public IntPtr hwnd; public uint message; public UIntPtr wParam; public IntPtr lParam; public uint time; public POINT pt; }
    [DllImport("user32.dll", SetLastError=true)] private static extern IntPtr SetWindowsHookEx(int id, HookProc callback, IntPtr module, uint threadId);
    [DllImport("user32.dll")] private static extern bool UnhookWindowsHookEx(IntPtr hook);
    [DllImport("user32.dll")] private static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll", CharSet=CharSet.Auto)] private static extern IntPtr GetModuleHandle(string name);
    [DllImport("user32.dll")] private static extern int GetMessage(out MSG message, IntPtr hwnd, uint min, uint max);
    [DllImport("user32.dll")] private static extern bool TranslateMessage(ref MSG message);
    [DllImport("user32.dll")] private static extern IntPtr DispatchMessage(ref MSG message);

    private const int WH_KEYBOARD_LL = 13, WH_MOUSE_LL = 14;
    private const uint LLKHF_INJECTED = 0x10, LLMHF_INJECTED = 0x01;
    private const int WM_KEYDOWN = 0x0100, WM_KEYUP = 0x0101, WM_SYSKEYDOWN = 0x0104, WM_SYSKEYUP = 0x0105;
    private const int WM_MOUSEMOVE = 0x0200, WM_LBUTTONDOWN = 0x0201, WM_LBUTTONUP = 0x0202;
    private const int WM_RBUTTONDOWN = 0x0204, WM_RBUTTONUP = 0x0205, WM_MBUTTONDOWN = 0x0207, WM_MBUTTONUP = 0x0208;
    private const int WM_MOUSEWHEEL = 0x020A, WM_XBUTTONDOWN = 0x020B, WM_XBUTTONUP = 0x020C;

    private static long lastPhysical = Environment.TickCount64 - 10000;
    private static readonly ConcurrentDictionary<uint, byte> keys = new ConcurrentDictionary<uint, byte>();
    private static readonly ConcurrentDictionary<int, byte> buttons = new ConcurrentDictionary<int, byte>();
    private static IntPtr keyboardHook, mouseHook;
    private static HookProc keyboardProc = KeyboardCallback;
    private static HookProc mouseProc = MouseCallback;

    private static void MarkPhysical() { Interlocked.Exchange(ref lastPhysical, Environment.TickCount64); }
    private static bool IsDown() { return !keys.IsEmpty || !buttons.IsEmpty; }

    private static IntPtr KeyboardCallback(int code, IntPtr wParam, IntPtr lParam) {
        if (code >= 0) {
            var value = Marshal.PtrToStructure<KBDLLHOOKSTRUCT>(lParam);
            if ((value.flags & LLKHF_INJECTED) == 0) {
                int message = wParam.ToInt32();
                if (message == WM_KEYDOWN || message == WM_SYSKEYDOWN) keys[value.vkCode] = 1;
                else if (message == WM_KEYUP || message == WM_SYSKEYUP) keys.TryRemove(value.vkCode, out _);
                MarkPhysical();
            }
        }
        return CallNextHookEx(keyboardHook, code, wParam, lParam);
    }

    private static IntPtr MouseCallback(int code, IntPtr wParam, IntPtr lParam) {
        if (code >= 0) {
            var value = Marshal.PtrToStructure<MSLLHOOKSTRUCT>(lParam);
            if ((value.flags & LLMHF_INJECTED) == 0) {
                int message = wParam.ToInt32();
                if (message == WM_LBUTTONDOWN) buttons[1] = 1;
                else if (message == WM_LBUTTONUP) buttons.TryRemove(1, out _);
                else if (message == WM_RBUTTONDOWN) buttons[3] = 1;
                else if (message == WM_RBUTTONUP) buttons.TryRemove(3, out _);
                else if (message == WM_MBUTTONDOWN) buttons[2] = 1;
                else if (message == WM_MBUTTONUP) buttons.TryRemove(2, out _);
                else if (message == WM_XBUTTONDOWN) buttons[4 + (int)((value.mouseData >> 16) & 0xffff)] = 1;
                else if (message == WM_XBUTTONUP) buttons.TryRemove(4 + (int)((value.mouseData >> 16) & 0xffff), out _);
                if (message == WM_MOUSEMOVE || message == WM_MOUSEWHEEL || message == WM_LBUTTONDOWN || message == WM_LBUTTONUP ||
                    message == WM_RBUTTONDOWN || message == WM_RBUTTONUP || message == WM_MBUTTONDOWN || message == WM_MBUTTONUP ||
                    message == WM_XBUTTONDOWN || message == WM_XBUTTONUP) MarkPhysical();
            }
        }
        return CallNextHookEx(mouseHook, code, wParam, lParam);
    }

    private static void ReportState() {
        long idle = Math.Max(0, Environment.TickCount64 - Interlocked.Read(ref lastPhysical));
        Console.WriteLine("STATE:" + idle + ":" + (IsDown() ? "1" : "0"));
        Console.Out.Flush();
    }

    public static void Run() {
        IntPtr module = GetModuleHandle(null);
        keyboardHook = SetWindowsHookEx(WH_KEYBOARD_LL, keyboardProc, module, 0);
        if (keyboardHook == IntPtr.Zero) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "Keyboard hook installation failed");
        mouseHook = SetWindowsHookEx(WH_MOUSE_LL, mouseProc, module, 0);
        if (mouseHook == IntPtr.Zero) {
            int error = Marshal.GetLastWin32Error();
            UnhookWindowsHookEx(keyboardHook);
            throw new System.ComponentModel.Win32Exception(error, "Mouse hook installation failed");
        }
        Console.WriteLine("READY"); Console.Out.Flush();
        var report = new Thread(() => { while (true) { ReportState(); Thread.Sleep(80); } });
        report.IsBackground = true; report.Start();
        try {
            MSG message;
            while (GetMessage(out message, IntPtr.Zero, 0, 0) > 0) { TranslateMessage(ref message); DispatchMessage(ref message); }
        } finally {
            UnhookWindowsHookEx(keyboardHook); UnhookWindowsHookEx(mouseHook);
        }
    }
}
'@
try {
    Add-Type -TypeDefinition $src -Language CSharp
    [NexxaPhysicalInputHooks]::Run()
} catch {
    [Console]::WriteLine('ERROR:' + $_.Exception.ToString())
    [Console]::Out.Flush()
    exit 2
}
""";
            Path dir = appDataDirectory();
            Files.createDirectories(dir);
            Path script = dir.resolve("physical-input-monitor.ps1");
            Files.writeString(script, source, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString())
                    .redirectErrorStream(true).start();
            LocalInputMonitor monitor = new LocalInputMonitor(process, console);
            boolean ready = monitor.readyLatch.await(12, TimeUnit.SECONDS);
            if (!ready || !monitor.healthy.get()) {
                String detail = monitor.startupError.get();
                if (detail.isBlank()) detail = monitor.diagnosticsText();
                monitor.close();
                throw new IOException("Windows could not activate the physical-input hooks. " +
                        (detail.isBlank() ? "The helper did not report READY within 12 seconds." : detail));
            }
            logTo(console, "Physical host-input hooks are READY. Injected Nexxa mouse/keyboard events are ignored; physical host input wins while active and for 450 ms after it stops.");
            return monitor;
        }

        boolean isLocalInputRecent() {
            if (!healthy.get() || !process.isAlive()) return false;
            if (physicalInputHeld.get()) return true;
            return System.nanoTime() - lastPhysicalInputNanos.get() < TimeUnit.MILLISECONDS.toNanos(PRIORITY_WINDOW_MS);
        }

        boolean isHealthy() { return healthy.get() && process.isAlive(); }

        @Override public void close() {
            try {
                process.destroy();
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (Exception ignored) { }
        }
    }

    private static final class HostServer {
        private final SSLContext context;
        private final String bindAddress;
        private final int port;
        private final byte[] accessKeyBytes;
        private final AtomicBoolean controlAllowed;
        private final AtomicBoolean hostPriority;
        private final AtomicBoolean clipboardAllowed;
        private final AtomicBoolean audioAllowed;
        private final StreamProfile profile;
        private final JTextArea console;
        private final JLabel viewerStatus;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean viewerActive = new AtomicBoolean(false);
        private final Set<Integer> pressedButtons = ConcurrentHashMap.newKeySet();
        private final Set<Integer> pressedKeys = ConcurrentHashMap.newKeySet();
        private volatile Robot sessionRobot;
        private volatile SSLServerSocket serverSocket;
        private volatile SSLSocket activeSocket;
        private volatile DataOutputStream activeOutput;
        private volatile LocalInputMonitor localInputMonitor;
        private final AtomicBoolean monitorStarting = new AtomicBoolean(false);
        private volatile boolean remoteControlRequested;
        private Thread acceptThread;

        HostServer(SSLContext context, String bindAddress, int port, String accessKey,
                   AtomicBoolean controlAllowed, AtomicBoolean hostPriority, AtomicBoolean clipboardAllowed, AtomicBoolean audioAllowed,
                   StreamProfile profile, JTextArea console, JLabel viewerStatus) {
            this.context = context; this.bindAddress = bindAddress; this.port = port;
            this.accessKeyBytes = accessKey.getBytes(StandardCharsets.UTF_8);
            this.controlAllowed = controlAllowed; this.hostPriority = hostPriority; this.clipboardAllowed = clipboardAllowed; this.audioAllowed = audioAllowed;
            this.profile = profile; this.console = console; this.viewerStatus = viewerStatus;
        }

        void updateHostPriorityMode() {
            if (!hostPriority.get()) {
                LocalInputMonitor previous = localInputMonitor;
                localInputMonitor = null;
                if (previous != null) previous.close();
                return;
            }
            if (!running.get() || !viewerActive.get() || !remoteControlRequested) return;
            LocalInputMonitor current = localInputMonitor;
            if (current != null && current.isHealthy()) return;
            if (!monitorStarting.compareAndSet(false, true)) return;
            Thread starter = new Thread(() -> {
                try {
                    LocalInputMonitor created = LocalInputMonitor.start(console);
                    if (running.get() && viewerActive.get() && hostPriority.get()) {
                        LocalInputMonitor replaced = localInputMonitor;
                        localInputMonitor = created;
                        if (replaced != null && replaced != created) replaced.close();
                    } else {
                        created.close();
                    }
                } catch (Exception e) {
                    logTo(console, "WARNING: Could not enable host physical-input priority: " + e.getMessage());
                    logTo(console, "Remote input remains available without automatic arbitration.");
                } finally {
                    monitorStarting.set(false);
                }
            }, "Nexxa-Input-Monitor-Starter");
            starter.setDaemon(true);
            starter.start();
        }

        void publishRemoteControlAvailability(boolean allowed) {
            DataOutputStream output = activeOutput;
            if (output == null || !remoteControlRequested) return;
            String message = allowed
                    ? "Host enabled remote keyboard/mouse control. Click the remote screen to send input."
                    : "Remote keyboard/mouse control is paused by the host.";
            try { writeTextMessage(output, MSG_CONTROL_STATUS, message); }
            catch (IOException e) { logTo(console, "Could not update viewer control status: " + e.getMessage()); }
        }

        void start() throws IOException {
            serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.setReceiveBufferSize(128 * 1024);
            setModernTls(serverSocket);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
            acceptThread = new Thread(this::acceptLoop, "Nexxa-Host-Accept");
            acceptThread.setDaemon(true); acceptThread.start();
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    SSLSocket socket = (SSLSocket) serverSocket.accept();
                    socket.setTcpNoDelay(true); socket.setKeepAlive(true);
                    // Smaller buffers reduce stale-frame backlog on slower links.
                    socket.setSendBufferSize(128 * 1024); socket.setReceiveBufferSize(128 * 1024);
                    if (!viewerActive.compareAndSet(false, true)) {
                        socket.close(); logTo(console, "A second viewer was rejected: this build supports one active viewer."); continue;
                    }
                    activeSocket = socket;
                    Thread session = new Thread(() -> handleViewer(socket), "Nexxa-Host-Session");
                    session.setDaemon(true); session.start();
                } catch (SocketException e) { if (running.get()) logTo(console, "Host listener error: " + e.getMessage()); }
                catch (Exception e) { if (running.get()) logTo(console, "Host accept error: " + e.getMessage()); }
            }
        }

        private void handleViewer(SSLSocket socket) {
            AtomicBoolean sessionRunning = new AtomicBoolean(false);
            AtomicReference<String> lastHostClipboard = new AtomicReference<>(null);
            AtomicReference<String> lastViewerClipboard = new AtomicReference<>(null);
            AudioLine audioLine = null;
            try (SSLSocket secure = socket) {
                setModernTls(secure);
                secure.setTcpNoDelay(true); secure.setKeepAlive(true);
                secure.setSendBufferSize(128 * 1024); secure.setReceiveBufferSize(128 * 1024);
                secure.setSoTimeout(15_000);
                secure.startHandshake();
                DataInputStream in = new DataInputStream(new BufferedInputStream(secure.getInputStream(), 32 * 1024));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(secure.getOutputStream(), 32 * 1024));
                int magic = in.readInt();
                String suppliedKey = in.readUTF();
                boolean requestControl = in.readBoolean();
                boolean requestClipboard = in.readBoolean();
                boolean requestAudio = in.readBoolean();
                remoteControlRequested = requestControl;
                boolean authenticated = magic == MAGIC && MessageDigest.isEqual(accessKeyBytes, suppliedKey.getBytes(StandardCharsets.UTF_8));
                out.writeBoolean(authenticated);
                if (!authenticated) {
                    out.writeUTF("Invitation key is invalid or has expired."); out.flush();
                    logTo(console, "Rejected viewer: invalid or expired invitation key."); return;
                }

                // Test the audio input before telling the viewer that audio is available.
                boolean audioGranted = requestAudio && audioAllowed.get();
                if (audioGranted) {
                    try {
                        audioLine = AudioLine.openCapture();
                        logTo(console, "Desktop audio capture selected: " + audioLine.deviceName + ".");
                    } catch (Exception audioError) {
                        audioGranted = false;
                        logTo(console, "Desktop audio unavailable: " + audioError.getMessage());
                    }
                }
                // The protocol accepts a control request; the host's live checkbox is the actual authority.
                boolean controlGranted = requestControl;
                boolean clipboardGranted = requestClipboard;
                out.writeBoolean(controlGranted); out.writeBoolean(clipboardGranted); out.writeBoolean(audioGranted); out.flush();
                activeOutput = out;
                if (requestControl) {
                    publishRemoteControlAvailability(controlAllowed.get());
                    if (!controlAllowed.get()) logTo(console, "Viewer requested keyboard/mouse control; waiting for the host to enable permission.");
                }
                secure.setSoTimeout(0);
                String remoteAddress = secure.getInetAddress().getHostAddress();
                SwingUtilities.invokeLater(() -> viewerStatus.setText("Connected viewer: " + remoteAddress));
                logTo(console, "Viewer authenticated from " + remoteAddress + ".");
                logTo(console, "Session requests: remote input=" + controlGranted + " (host approval required), clipboard=" + clipboardGranted + ", desktop audio=" + audioGranted + ".");
                Robot robot = new Robot(); sessionRobot = robot;
                if (requestControl && hostPriority.get()) {
                    try {
                        localInputMonitor = LocalInputMonitor.start(console);
                    } catch (Exception hookError) {
                        // Do not turn a hook startup problem into a permanent remote-input lockout.
                        // Remote control stays available; the console makes degraded arbitration explicit.
                        logTo(console, "WARNING: Host physical-input priority could not start: " + hookError.getMessage());
                        logTo(console, "Remote control remains enabled, but host-vs-remote input arbitration is unavailable until the Windows hooks can be started.");
                    }
                } else if (requestControl) {
                    logTo(console, "Host physical-input priority is disabled by the host. Remote input is enabled.");
                }
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                int screenWidth = screen.width, screenHeight = screen.height;
                sessionRunning.set(true);

                Thread frameThread = new Thread(() -> streamFrames(robot, screenWidth, screenHeight, out, secure, sessionRunning), "Nexxa-Screen-Stream");
                // Input is handled by the normal-priority session thread. Keep expensive
                // screen capture/encoding below it so the physical host stays responsive.
                frameThread.setPriority(Thread.MIN_PRIORITY);
                frameThread.setDaemon(true); frameThread.start();

                Thread cursorThread = new Thread(() -> streamCursor(out, secure, sessionRunning, screenWidth, screenHeight), "Nexxa-Remote-Cursor");
                cursorThread.setPriority(Thread.NORM_PRIORITY - 1);
                cursorThread.setDaemon(true); cursorThread.start();

                if (clipboardGranted) {
                    Thread clipboardThread = new Thread(() -> streamClipboard(out, secure, sessionRunning, lastHostClipboard), "Nexxa-Host-Clipboard");
                    clipboardThread.setPriority(Thread.MIN_PRIORITY);
                    clipboardThread.setDaemon(true); clipboardThread.start();
                }

                if (audioGranted && audioLine != null) {
                    AudioLine captured = audioLine;
                    Thread audioThread = new Thread(() -> streamAudio(captured, out, secure, sessionRunning), "Nexxa-Desktop-Audio");
                    audioThread.setPriority(Thread.MIN_PRIORITY);
                    audioThread.setDaemon(true); audioThread.start();
                    logTo(console, "Desktop audio stream started.");
                }

                LocalInputMonitor initialMonitor = localInputMonitor;
                boolean monitorHealthy = initialMonitor != null && initialMonitor.isHealthy();
                boolean hostInputBusy = shouldPauseRemoteForHostInput(hostPriority.get(), monitorHealthy,
                        monitorHealthy && initialMonitor.isLocalInputRecent());
                if (hostInputBusy) writeTextMessage(out, MSG_CONTROL_STATUS, "Host physical input is active — remote input is temporarily paused.");
                boolean monitorFailureLogged = false;
                while (running.get() && sessionRunning.get()) {
                    int type;
                    try { type = in.readUnsignedByte(); }
                    catch (EOFException | SocketException e) { break; }
                    LocalInputMonitor currentMonitor = localInputMonitor;
                    monitorHealthy = currentMonitor != null && currentMonitor.isHealthy();
                    if (hostPriority.get() && currentMonitor != null && !monitorHealthy && !monitorFailureLogged) {
                        monitorFailureLogged = true;
                        logTo(console, "WARNING: Physical-input monitor exited. Remote input has been re-enabled to avoid locking out the viewer; automatic host priority is temporarily unavailable.");
                        writeTextMessage(out, MSG_CONTROL_STATUS, "Host-priority monitor unavailable. Remote control remains enabled without automatic host-input arbitration.");
                    }
                    boolean localBusy = shouldPauseRemoteForHostInput(hostPriority.get(), monitorHealthy,
                            monitorHealthy && currentMonitor.isLocalInputRecent());
                    boolean mayControl = requestControl && controlAllowed.get() && controlGranted && !localBusy;
                    if (localBusy && !hostInputBusy) {
                        releaseHeldInputs();
                        writeTextMessage(out, MSG_CONTROL_STATUS, "Host is using the keyboard/mouse — remote input paused.");
                        logTo(console, "Remote input paused because physical host input has priority.");
                    } else if (!localBusy && hostInputBusy) {
                        writeTextMessage(out, MSG_CONTROL_STATUS, "Host is idle — remote input resumed.");
                        logTo(console, "Host is idle; remote input resumed.");
                    }
                    hostInputBusy = localBusy;
                    switch (type) {
                        case MSG_MOUSE_MOVE -> {
                            int nx = in.readUnsignedShort(), ny = in.readUnsignedShort();
                            if (mayControl) robot.mouseMove(clamp((int) Math.round(nx / 65535.0 * (screenWidth - 1)), 0, screenWidth - 1),
                                    clamp((int) Math.round(ny / 65535.0 * (screenHeight - 1)), 0, screenHeight - 1));
                        }
                        case MSG_MOUSE_PRESS, MSG_MOUSE_RELEASE -> {
                            int button = in.readUnsignedByte(), mask = mouseMask(button);
                            if (mask != 0) {
                                if (type == MSG_MOUSE_PRESS && mayControl) { robot.mousePress(mask); pressedButtons.add(button); }
                                else if (type == MSG_MOUSE_RELEASE && pressedButtons.remove(button)) robot.mouseRelease(mask);
                            }
                        }
                        case MSG_KEY_PRESS, MSG_KEY_RELEASE -> {
                            int keyCode = in.readInt();
                            if (keyCode > 0 && keyCode <= 0xFFFF) {
                                try {
                                    if (type == MSG_KEY_PRESS && mayControl) { robot.keyPress(keyCode); pressedKeys.add(keyCode); }
                                    else if (type == MSG_KEY_RELEASE && pressedKeys.remove(keyCode)) robot.keyRelease(keyCode);
                                } catch (IllegalArgumentException ignored) { }
                            }
                        }
                        case MSG_MOUSE_WHEEL -> { int amount = in.readInt(); if (mayControl) robot.mouseWheel(clamp(amount, -20, 20)); }
                        case MSG_VIEWER_CLIPBOARD -> {
                            String text = readTextMessage(in);
                            if (clipboardGranted && clipboardAllowed.get()) {
                                lastViewerClipboard.set(text); lastHostClipboard.set(text); setLocalClipboardText(text);
                            }
                        }
                        default -> throw new IOException("Unknown control message " + type + ".");
                    }
                    if (!controlAllowed.get() && (!pressedButtons.isEmpty() || !pressedKeys.isEmpty())) releaseHeldInputs();
                }
                sessionRunning.set(false);
                try { frameThread.join(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                logTo(console, "Viewer disconnected.");
            } catch (Exception e) {
                if (running.get()) logTo(console, "Remote session ended: " + e.getMessage());
            } finally {
                sessionRunning.set(false);
                if (audioLine != null) audioLine.close();
                LocalInputMonitor endingMonitor = localInputMonitor;
                localInputMonitor = null;
                if (endingMonitor != null) endingMonitor.close();
                remoteControlRequested = false;
                activeOutput = null;
                releaseHeldInputs();
                sessionRobot = null;
                activeSocket = null;
                viewerActive.set(false);
                SwingUtilities.invokeLater(() -> viewerStatus.setText("No active Nexxa viewer"));
            }
        }

        private void streamFrames(Robot robot, int screenWidth, int screenHeight, DataOutputStream out,
                                  SSLSocket socket, AtomicBoolean sessionRunning) {
            Rectangle bounds = new Rectangle(0, 0, screenWidth, screenHeight);
            long period = TimeUnit.SECONDS.toNanos(1) / profile.fps();
            long nextFrameAt = System.nanoTime();
            long metricsStart = System.nanoTime();
            int frames = 0;
            long encodeNanos = 0;
            long sendNanos = 0;
            try (JpegEncoder encoder = new JpegEncoder()) {
                logTo(console, String.format(Locale.ROOT,
                        "Video preset: %s | JPEG quality %.0f%% | frame limit %d MiB",
                        profile.label(), profile.jpegQuality() * 100.0, MAX_FRAME_BYTES / (1024 * 1024)));
                while (running.get() && sessionRunning.get() && !socket.isClosed()) {
                    long began = System.nanoTime();
                    BufferedImage capture = robot.createScreenCapture(bounds);
                    byte[] frame = encoder.encode(capture, profile);
                    long encoded = System.nanoTime();
                    encodeNanos += encoded - began;
                    synchronized (out) {
                        long sendStarted = System.nanoTime();
                        out.writeByte(MSG_FRAME);
                        out.writeInt(frame.length);
                        out.write(frame);
                        out.flush();
                        sendNanos += System.nanoTime() - sendStarted;
                    }
                    frames++;
                    long now = System.nanoTime();
                    if (now - metricsStart >= TimeUnit.SECONDS.toNanos(5)) {
                        double fps = frames / ((now - metricsStart) / 1_000_000_000.0);
                        double avgEncodeMs = encodeNanos / 1_000_000.0 / Math.max(1, frames);
                        double avgSendMs = sendNanos / 1_000_000.0 / Math.max(1, frames);
                        logTo(console, String.format(Locale.ROOT,
                                "Stream: %.1f FPS | capture+encode %.1f ms | send %.1f ms | frame %.0f KB",
                                fps, avgEncodeMs, avgSendMs, frame.length / 1024.0));
                        frames = 0; encodeNanos = 0; sendNanos = 0; metricsStart = now;
                    }
                    nextFrameAt += period;
                    long wait = nextFrameAt - System.nanoTime();
                    if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                    else nextFrameAt = System.nanoTime(); // Skip missed periods; do not queue stale captures.
                }
            } catch (Exception e) {
                if (sessionRunning.get()) logTo(console, "Screen stream stopped: " + e.getMessage());
            }
        }

        private void streamCursor(DataOutputStream out, SSLSocket socket, AtomicBoolean sessionRunning,
                                  int screenWidth, int screenHeight) {
            while (running.get() && sessionRunning.get() && !socket.isClosed()) {
                try {
                    PointerInfo pointer = MouseInfo.getPointerInfo();
                    if (pointer != null) {
                        Point point = pointer.getLocation();
                        int nx = clamp((int) Math.round(point.x / (double) Math.max(1, screenWidth - 1) * 65535.0), 0, 65535);
                        int ny = clamp((int) Math.round(point.y / (double) Math.max(1, screenHeight - 1) * 65535.0), 0, 65535);
                        synchronized (out) { out.writeByte(MSG_REMOTE_CURSOR); out.writeShort(nx); out.writeShort(ny); out.flush(); }
                    }
                    Thread.sleep(33); // ~30 Hz pointer sync, independent of video frame rate.
                } catch (Exception e) {
                    if (sessionRunning.get()) logTo(console, "Remote cursor sync stopped: " + e.getMessage());
                    return;
                }
            }
        }

        private void streamClipboard(DataOutputStream out, SSLSocket socket, AtomicBoolean sessionRunning,
                                     AtomicReference<String> lastRemote) {
            String lastSent = localClipboardText();
            while (running.get() && sessionRunning.get() && !socket.isClosed()) {
                try {
                    String text = localClipboardText();
                    if (clipboardAllowed.get() && text != null && text.length() * 3L <= MAX_CLIPBOARD_BYTES && !text.equals(lastSent) && !text.equals(lastRemote.get())) {
                        writeTextMessage(out, MSG_HOST_CLIPBOARD, text); lastSent = text;
                    }
                    Thread.sleep(500);
                } catch (Exception e) { if (sessionRunning.get()) logTo(console, "Host clipboard sync stopped: " + e.getMessage()); return; }
            }
        }

        private void streamAudio(AudioLine capture, DataOutputStream out, SSLSocket socket, AtomicBoolean sessionRunning) {
            byte[] buffer = new byte[960]; // 20 ms at 24 kHz mono, signed 16-bit.
            try {
                while (running.get() && sessionRunning.get() && !socket.isClosed()) {
                    int count = capture.line.read(buffer, 0, buffer.length);
                    if (count > 0) synchronized (out) {
                        out.writeByte(MSG_AUDIO); out.writeInt(count); out.write(buffer, 0, count); out.flush();
                    }
                }
            } catch (Exception e) { if (sessionRunning.get()) logTo(console, "Desktop audio stream stopped: " + e.getMessage()); }
        }

        void releaseHeldInputs() {
            Robot robot = sessionRobot; if (robot == null) return;
            for (int button : new ArrayList<>(pressedButtons)) { int mask = mouseMask(button); if (mask != 0) try { robot.mouseRelease(mask); } catch (Exception ignored) { } }
            for (int key : new ArrayList<>(pressedKeys)) try { robot.keyRelease(key); } catch (Exception ignored) { }
            pressedButtons.clear(); pressedKeys.clear();
        }

        void stop() {
            running.set(false);
            try { if (activeSocket != null) activeSocket.close(); } catch (IOException ignored) { }
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
            Arrays.fill(accessKeyBytes, (byte) 0);
            LocalInputMonitor monitor = localInputMonitor; localInputMonitor = null;
            if (monitor != null) monitor.close();
            releaseHeldInputs();
        }
    }

    /** Java Sound can use a Windows recording endpoint such as Stereo Mix/What U Hear.
     *  Windows does not expose WASAPI loopback uniformly through the standard Java API;
     *  this is therefore an optional best-effort path, not universal system-audio capture. */
    private static final class AudioLine {
        static final AudioFormat FORMAT = new AudioFormat(24_000f, 16, 1, true, false);
        final TargetDataLine line;
        final String deviceName;
        private AudioLine(TargetDataLine line, String deviceName) { this.line = line; this.deviceName = deviceName; }
        static String describeCaptureDevices() {
            StringBuilder out = new StringBuilder("Audio capture endpoints:");
            boolean found = false;
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(info);
                boolean hasTarget = Arrays.stream(mixer.getTargetLineInfo()).anyMatch(line -> TargetDataLine.class.isAssignableFrom(line.getLineClass()));
                if (hasTarget) {
                    out.append(System.lineSeparator()).append(" - ").append(info.getName()).append(" — ").append(info.getDescription());
                    found = true;
                }
            }
            if (!found) out.append(System.lineSeparator()).append(" - No recording endpoints exposed by Java Sound.");
            out.append(System.lineSeparator()).append("Desktop audio requires a loopback recording endpoint such as Stereo Mix; a normal microphone is not desktop audio.");
            return out.toString();
        }

        static AudioLine openCapture() throws LineUnavailableException {
            String[] terms = {"stereo mix", "what u hear", "waveout mix", "loopback"};
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                String name = (mixerInfo.getName() + " " + mixerInfo.getDescription()).toLowerCase(Locale.ROOT);
                boolean candidate = Arrays.stream(terms).anyMatch(name::contains);
                if (!candidate) continue;
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                    try {
                        Line line = mixer.getLine(lineInfo);
                        if (line instanceof TargetDataLine target) {
                            target.open(FORMAT, 8192); target.start();
                            return new AudioLine(target, mixerInfo.getName());
                        }
                    } catch (Exception ignored) { }
                }
            }
            throw new LineUnavailableException("Windows did not expose Stereo Mix, What U Hear or another supported loopback input. Enable a loopback recording device or use a future WASAPI-native build.");
        }
        static SourceDataLine openPlayback() throws LineUnavailableException {
            SourceDataLine output = AudioSystem.getSourceDataLine(FORMAT);
            output.open(FORMAT, 8192); output.start(); return output;
        }
        void close() { try { line.stop(); line.close(); } catch (Exception ignored) { } }
    }

    @SuppressWarnings("serial")
    private static final class ViewerPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final JTextArea console;
        private final TailscaleManager tailscale;
        private final MainWindow window;
        private final JTextField inviteField = new JTextField();
        private final JButton pasteButton = new JButton("Paste invite");
        private final JButton connectButton = new JButton("Connect");
        private final JButton disconnectButton = new JButton("Disconnect");
        private final JButton fullScreenButton = new JButton("Full screen");
        private final JCheckBox requestControl = new JCheckBox("Request keyboard/mouse control (host must allow it)", true);
        private final JCheckBox syncClipboard = new JCheckBox("Sync clipboard", true);
        private final JCheckBox receiveAudio = new JCheckBox("Receive audio (experimental)", false);
        private final JLabel status = new JLabel("Ready to connect");
        private final JLabel metrics = new JLabel("Waiting for a session");
        private final ScreenPanel screenPanel = new ScreenPanel();
        private final CardLayout toolbarLayout = new CardLayout();
        private final JPanel toolbarCards = new JPanel(toolbarLayout);
        private final AtomicReference<Point> pendingMove = new AtomicReference<>();
        private final Set<Integer> forwardedKeys = ConcurrentHashMap.newKeySet();
        private final Set<Integer> forwardedButtons = ConcurrentHashMap.newKeySet();
        private final KeyEventDispatcher remoteKeyDispatcher = this::dispatchRemoteKeyEvent;
        private final ScheduledExecutorService inputScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Nexxa-Input-Coalescer"); t.setDaemon(true); return t;
        });
        private volatile SSLSocket socket;
        private volatile SSLSocket connectingSocket;
        private volatile DataOutputStream out;
        private volatile boolean connected;
        private final AtomicBoolean connectCancelled = new AtomicBoolean(false);
        private volatile boolean controlGranted;
        private volatile boolean clipboardGranted;
        private volatile boolean audioGranted;
        private volatile String lastRemoteClipboard;
        private volatile String lastLocalClipboardSent;
        private volatile SourceDataLine playbackLine;
        private volatile BlockingQueue<byte[]> audioQueue;
        private final AtomicReference<byte[]> newestFrame = new AtomicReference<>();
        private volatile AtomicBoolean frameDecoderRunning = new AtomicBoolean(false);

        ViewerPanel(JTextArea console, TailscaleManager tailscale, MainWindow window) {
            this.console = console; this.tailscale = tailscale; this.window = window;
            setLayout(new BorderLayout(7, 7)); setBackground(BG); setBorder(new EmptyBorder(8, 8, 8, 8));
            styleInput(inviteField);
            styleButton(pasteButton, false); styleButton(connectButton, true); styleButton(disconnectButton, false); styleButton(fullScreenButton, false);
            styleCheckBox(requestControl); styleCheckBox(syncClipboard); styleCheckBox(receiveAudio);

            JPanel setup = new JPanel(new BorderLayout(7, 7)); setup.setBackground(PANEL);
            setup.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(48, 65, 89)), new EmptyBorder(8, 10, 8, 10)));
            JPanel inviteRow = new JPanel(new BorderLayout(7, 0)); inviteRow.setOpaque(false);
            JLabel inviteLabel = new JLabel("Invitation"); inviteLabel.setForeground(MUTED); inviteLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
            JPanel inviteCenter = new JPanel(new BorderLayout(6, 0)); inviteCenter.setOpaque(false);
            inviteCenter.add(inviteField, BorderLayout.CENTER);
            JPanel inviteActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); inviteActions.setOpaque(false);
            inviteActions.add(pasteButton); inviteActions.add(connectButton);
            inviteCenter.add(inviteActions, BorderLayout.EAST);
            inviteRow.add(inviteLabel, BorderLayout.WEST); inviteRow.add(inviteCenter, BorderLayout.CENTER);
            JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0)); options.setOpaque(false);
            options.add(requestControl); options.add(syncClipboard); options.add(receiveAudio);
            setup.add(inviteRow, BorderLayout.CENTER); setup.add(options, BorderLayout.SOUTH);

            JPanel sessionToolbar = new JPanel(new BorderLayout(8, 0)); sessionToolbar.setBackground(PANEL);
            sessionToolbar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(48, 65, 89)), new EmptyBorder(7, 10, 7, 10)));
            JPanel sessionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); sessionActions.setOpaque(false);
            sessionActions.add(disconnectButton); sessionActions.add(fullScreenButton);
            JPanel labels = new JPanel(new GridLayout(0, 1, 1, 2)); labels.setOpaque(false);
            status.setForeground(GREEN); status.setFont(new Font("Segoe UI", Font.BOLD, 12));
            metrics.setForeground(MUTED); metrics.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            labels.add(status); labels.add(metrics);
            sessionToolbar.add(labels, BorderLayout.CENTER); sessionToolbar.add(sessionActions, BorderLayout.EAST);
            toolbarCards.setOpaque(false);
            toolbarCards.add(setup, "setup"); toolbarCards.add(sessionToolbar, "session");

            screenPanel.setBackground(new Color(3, 5, 9));
            screenPanel.setFocusable(true);
            JPanel viewport = new JPanel(new BorderLayout()); viewport.setBackground(Color.BLACK);
            viewport.setBorder(BorderFactory.createLineBorder(new Color(44, 60, 81)));
            viewport.add(screenPanel, BorderLayout.CENTER);
            add(toolbarCards, BorderLayout.NORTH);
            add(viewport, BorderLayout.CENTER);

            disconnectButton.setEnabled(false);
            pasteButton.addActionListener(e -> { String text = localClipboardText(); if (text != null) inviteField.setText(text.trim()); });
            connectButton.addActionListener(e -> connect()); disconnectButton.addActionListener(e -> disconnect());
            fullScreenButton.addActionListener(e -> window.toggleFullScreen());
            installInputListeners();
            toolbarLayout.show(toolbarCards, "setup");
        }

        void setFullScreenButtonText(String value) { fullScreenButton.setText(value); }

        private volatile ScheduledFuture<?> inputMoveTask;

        private void startInputCoalescer() {
            ScheduledFuture<?> current = inputMoveTask;
            if (current != null && !current.isDone() && !current.isCancelled()) return;
            inputMoveTask = inputScheduler.scheduleAtFixedRate(() -> {
                Point point = pendingMove.getAndSet(null);
                if (point != null) sendMouseMove(point);
            }, 0, 8, TimeUnit.MILLISECONDS);
        }

        void setInvitation(String value) { SwingUtilities.invokeLater(() -> inviteField.setText(value == null ? "" : value.trim())); }

        private void installInputListeners() {
            screenPanel.setFocusable(true);
            screenPanel.setFocusTraversalKeysEnabled(false);
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(remoteKeyDispatcher);
            screenPanel.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) { releaseAllRemoteInputs(); }
            });
            screenPanel.addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) { pendingMove.set(e.getPoint()); }
                @Override public void mouseDragged(MouseEvent e) { pendingMove.set(e.getPoint()); }
            });
            screenPanel.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    screenPanel.requestFocusInWindow(); sendMouseMove(e.getPoint()); sendButton(MSG_MOUSE_PRESS, e.getButton());
                }
                @Override public void mouseReleased(MouseEvent e) { sendMouseMove(e.getPoint()); sendButton(MSG_MOUSE_RELEASE, e.getButton()); }
            });
            screenPanel.addMouseWheelListener(e -> sendInt(MSG_MOUSE_WHEEL, e.getWheelRotation()));
        }

        private boolean dispatchRemoteKeyEvent(KeyEvent event) {
            if (!connected || !controlGranted || !window.isActive() || !screenPanel.isFocusOwner()) return false;
            int id = event.getID();
            if (id != KeyEvent.KEY_PRESSED && id != KeyEvent.KEY_RELEASED) return false;
            int code = event.getKeyCode();
            if (code == KeyEvent.VK_UNDEFINED) return false;
            sendRemoteKey(id == KeyEvent.KEY_PRESSED, code);
            event.consume();
            return true;
        }

        private void sendRemoteKey(boolean pressed, int keyCode) {
            if (!connected || !controlGranted) return;
            if (pressed) forwardedKeys.add(keyCode); else forwardedKeys.remove(keyCode);
            sendInt(pressed ? MSG_KEY_PRESS : MSG_KEY_RELEASE, keyCode);
        }

        private void releaseAllRemoteInputs() {
            if (!connected || !controlGranted) { forwardedKeys.clear(); forwardedButtons.clear(); return; }
            for (int key : new ArrayList<>(forwardedKeys)) sendInt(MSG_KEY_RELEASE, key);
            for (int button : new ArrayList<>(forwardedButtons)) sendButton(MSG_MOUSE_RELEASE, button);
            forwardedKeys.clear(); forwardedButtons.clear();
        }

        private void sendMouseMove(Point point) {
            if (!connected || !controlGranted) return;
            Rectangle rect = screenPanel.getImageRectangle();
            if (rect == null || !rect.contains(point)) return;
            int nx = (int) Math.round((point.x - rect.x) / (double) Math.max(1, rect.width - 1) * 65535.0);
            int ny = (int) Math.round((point.y - rect.y) / (double) Math.max(1, rect.height - 1) * 65535.0);
            DataOutputStream target = out;
            if (target == null) return;
            synchronized (target) {
                try { target.writeByte(MSG_MOUSE_MOVE); target.writeShort(clamp(nx, 0, 65535)); target.writeShort(clamp(ny, 0, 65535)); target.flush(); }
                catch (IOException e) { disconnectWithMessage("Input channel lost: " + e.getMessage()); }
            }
        }
        private void sendButton(int type, int button) {
            if (!connected || !controlGranted) return;
            if (type == MSG_MOUSE_PRESS) forwardedButtons.add(button);
            else if (type == MSG_MOUSE_RELEASE) forwardedButtons.remove(button);
            DataOutputStream target = out;
            if (target == null) return;
            synchronized (target) {
                try { target.writeByte(type); target.writeByte(button); target.flush(); }
                catch (IOException e) { disconnectWithMessage("Input channel lost: " + e.getMessage()); }
            }
        }
        private void sendInt(int type, int value) {
            if (!connected || !controlGranted) return;
            DataOutputStream target = out;
            if (target == null) return;
            synchronized (target) {
                try { target.writeByte(type); target.writeInt(value); target.flush(); }
                catch (IOException e) { disconnectWithMessage("Input channel lost: " + e.getMessage()); }
            }
        }

        private void connect() {
            if (connected) return;
            final Invite invite;
            try {
                invite = parseInvite(inviteField.getText());
                if (!tailscale.isReady()) throw new IllegalArgumentException("Tailscale is not connected. Wait for the connected status at the top of the window.");
            } catch (Exception e) { JOptionPane.showMessageDialog(this, e.getMessage(), APP_NAME, JOptionPane.WARNING_MESSAGE); return; }
            connectCancelled.set(false);
            connectButton.setEnabled(false); status.setText("Connecting securely..."); screenPanel.setStatus("Connecting securely to " + invite.host());
            Thread worker = new Thread(() -> doConnect(invite), "Nexxa-Viewer-Connect"); worker.setDaemon(true); worker.start();
        }

        private void doConnect(Invite invite) {
            SSLSocket secure = null;
            try {
                SSLContext context = createPinnedClientContext(invite.fingerprint());
                secure = (SSLSocket) context.getSocketFactory().createSocket(); connectingSocket = secure;
                if (connectCancelled.get()) throw new IOException("Connection cancelled.");
                secure.setTcpNoDelay(true); secure.setKeepAlive(true); secure.setSendBufferSize(128 * 1024); secure.setReceiveBufferSize(128 * 1024);
                secure.connect(new InetSocketAddress(invite.host(), invite.port()), 12_000);
                if (connectCancelled.get()) throw new IOException("Connection cancelled.");
                setModernTls(secure); secure.setSoTimeout(15_000); secure.startHandshake();
                DataInputStream in = new DataInputStream(new BufferedInputStream(secure.getInputStream(), 32 * 1024));
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(secure.getOutputStream(), 32 * 1024));
                output.writeInt(MAGIC); output.writeUTF(invite.accessKey());
                output.writeBoolean(requestControl.isSelected()); output.writeBoolean(syncClipboard.isSelected()); output.writeBoolean(receiveAudio.isSelected()); output.flush();
                boolean authenticated = in.readBoolean();
                if (!authenticated) throw new IOException(in.readUTF());
                controlGranted = in.readBoolean(); clipboardGranted = in.readBoolean(); audioGranted = in.readBoolean();
                secure.setSoTimeout(0); socket = secure; connectingSocket = null; out = output; connected = true;
                startInputCoalescer(); newestFrame.set(null); startFrameDecoder(); if (audioGranted) startAudioPlayback();
                SwingUtilities.invokeLater(() -> {
                    window.setViewerConnectedLayout(true);
                    connectButton.setEnabled(false); disconnectButton.setEnabled(true); toolbarLayout.show(toolbarCards, "session");
                    status.setText("Connected to " + invite.host());
                    metrics.setText("Video initializing  •  host controls physical-input priority");
                    screenPanel.setStatus("Connected. Waiting for the first frame...");
                    try {
                        BufferedImage transparent = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                        Cursor hidden = Toolkit.getDefaultToolkit().createCustomCursor(transparent, new Point(0, 0), "Nexxa-hidden-cursor");
                        screenPanel.setCursor(hidden);
                    } catch (Exception ignored) { }
                    screenPanel.requestFocusInWindow();
                });
                logTo(console, "Connected to " + invite.host() + ". Remote input=" + controlGranted + ", clipboard=" + clipboardGranted + ", audio=" + audioGranted + ".");
                if (syncClipboard.isSelected() && !clipboardGranted) logTo(console, "Clipboard sync was not granted by the host.");
                if (receiveAudio.isSelected() && !audioGranted) logTo(console, "Desktop audio was not enabled or available on the host.");
                if (clipboardGranted) startClipboardSender();
                readMessages(in);
            } catch (Exception e) {
                try { if (secure != null) secure.close(); } catch (IOException ignored) { }
                if (connectingSocket == secure) connectingSocket = null;
                boolean cancelled = connectCancelled.get(); disconnect();
                if (!cancelled) logTo(console, "Connection failed: " + e.getMessage());
            } finally { if (connectingSocket == secure) connectingSocket = null; }
        }

        private void readMessages(DataInputStream in) throws IOException {
            while (connected) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case MSG_FRAME -> {
                        int length = in.readInt(); if (length <= 0 || length > MAX_FRAME_BYTES) throw new IOException("Invalid video frame length.");
                        byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new EOFException("Incomplete screen frame.");
                        newestFrame.set(bytes);
                    }
                    case MSG_HOST_CLIPBOARD -> {
                        String text = readTextMessage(in);
                        if (clipboardGranted) { lastRemoteClipboard = text; lastLocalClipboardSent = text; setLocalClipboardText(text); }
                    }
                    case MSG_AUDIO -> {
                        int length = in.readInt(); if (length <= 0 || length > MAX_AUDIO_BYTES) throw new IOException("Invalid audio packet length.");
                        byte[] audio = in.readNBytes(length); if (audio.length != length) throw new EOFException("Incomplete audio packet.");
                        if (audioGranted) enqueueAudio(audio);
                    }
                    case MSG_CONTROL_STATUS -> {
                        String message = readTextMessage(in);
                        SwingUtilities.invokeLater(() -> status.setText(message));
                    }
                    case MSG_REMOTE_CURSOR -> {
                        int x = in.readUnsignedShort(), y = in.readUnsignedShort();
                        screenPanel.setRemoteCursor(x, y);
                    }
                    default -> throw new IOException("Unexpected server message " + type + ".");
                }
            }
        }

        private void startFrameDecoder() {
            AtomicBoolean decoderActive = new AtomicBoolean(true); frameDecoderRunning = decoderActive;
            Thread decoder = new Thread(() -> {
                while (decoderActive.get() && connected) {
                    byte[] encoded = newestFrame.getAndSet(null);
                    if (encoded == null) { try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } continue; }
                    try {
                        BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
                        if (image != null && decoderActive.get()) {
                            screenPanel.setFrame(image); double fps = screenPanel.fps(); int sizeKb = encoded.length / 1024;
                            int frameWidth = image.getWidth(), frameHeight = image.getHeight();
                            SwingUtilities.invokeLater(() -> { if (connected) metrics.setText(String.format(Locale.ROOT, "%.1f FPS  •  %d KB/frame  •  %dx%d  •  %s", fps, sizeKb, frameWidth, frameHeight, controlGranted ? "Remote control requested" : "View only")); });
                        }
                    } catch (IOException e) { logTo(console, "Could not decode a screen frame: " + e.getMessage()); }
                }
            }, "Nexxa-Frame-Decoder");
            decoder.setPriority(Thread.NORM_PRIORITY - 1); decoder.setDaemon(true); decoder.start();
        }

        private void startClipboardSender() {
            lastLocalClipboardSent = localClipboardText();
            Thread t = new Thread(() -> {
                while (connected && clipboardGranted) {
                    try {
                        String text = localClipboardText();
                        if (text != null && text.length() * 3L <= MAX_CLIPBOARD_BYTES && !text.equals(lastLocalClipboardSent) && !text.equals(lastRemoteClipboard)) {
                            writeTextMessage(out, MSG_VIEWER_CLIPBOARD, text); lastLocalClipboardSent = text;
                        }
                        Thread.sleep(400);
                    } catch (Exception e) { if (connected) logTo(console, "Viewer clipboard sync stopped: " + e.getMessage()); return; }
                }
            }, "Nexxa-Viewer-Clipboard"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); t.start();
        }

        private void startAudioPlayback() {
            try {
                playbackLine = AudioLine.openPlayback(); audioQueue = new ArrayBlockingQueue<>(10);
                BlockingQueue<byte[]> queue = audioQueue; SourceDataLine line = playbackLine;
                Thread player = new Thread(() -> {
                    try { while (connected || !queue.isEmpty()) { byte[] chunk = queue.poll(250, TimeUnit.MILLISECONDS); if (chunk != null) line.write(chunk, 0, chunk.length); } }
                    catch (Exception e) { if (connected) logTo(console, "Audio playback error: " + e.getMessage()); }
                }, "Nexxa-Audio-Playback"); player.setPriority(Thread.MIN_PRIORITY); player.setDaemon(true); player.start();
            } catch (Exception e) { logTo(console, "Could not open local audio output: " + e.getMessage()); }
        }
        private void enqueueAudio(byte[] bytes) {
            BlockingQueue<byte[]> queue = audioQueue; if (queue == null) return;
            if (!queue.offer(bytes)) { queue.poll(); queue.offer(bytes); }
        }

        void disconnect() {
            releaseAllRemoteInputs();
            connectCancelled.set(true); connected = false; frameDecoderRunning.set(false); newestFrame.set(null);
            ScheduledFuture<?> moveTask = inputMoveTask; inputMoveTask = null; if (moveTask != null) moveTask.cancel(false);
            controlGranted = false; clipboardGranted = false; audioGranted = false;
            SSLSocket pending = connectingSocket; connectingSocket = null; try { if (pending != null) pending.close(); } catch (IOException ignored) { }
            SSLSocket current = socket; socket = null; out = null; try { if (current != null) current.close(); } catch (IOException ignored) { }
            SourceDataLine line = playbackLine; playbackLine = null;
            if (line != null) { try { line.stop(); line.flush(); line.close(); } catch (Exception ignored) { } }
            BlockingQueue<byte[]> q = audioQueue; if (q != null) q.clear(); audioQueue = null; pendingMove.set(null);
            SwingUtilities.invokeLater(() -> {
                window.setViewerConnectedLayout(false);
                connectButton.setEnabled(true); disconnectButton.setEnabled(false); toolbarLayout.show(toolbarCards, "setup");
                status.setText("Disconnected"); metrics.setText("Ready for a new session"); screenPanel.clear("Paste a Nexxa invitation to connect.");
            });
        }
        void shutdown() {
            disconnect();
            inputScheduler.shutdownNow();
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(remoteKeyDispatcher);
        }
        private void disconnectWithMessage(String message) { disconnect(); logTo(console, message); }
    }

    @SuppressWarnings("serial")
    private static final class ScreenPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private volatile BufferedImage frame;
        private volatile String statusText = "Paste a Nexxa invitation to connect.";
        private volatile int remoteCursorX = -1;
        private volatile int remoteCursorY = -1;
        private final AtomicLong frameCount = new AtomicLong();
        private volatile long fpsWindowStart = System.nanoTime();
        private volatile double currentFps;
        void setFrame(BufferedImage image) { frame = image; statusText = ""; frameCount.incrementAndGet(); repaint(); }
        void setStatus(String value) { statusText = value; repaint(); }
        void setRemoteCursor(int x, int y) { remoteCursorX = x; remoteCursorY = y; repaint(); }
        void clear(String value) { frame = null; statusText = value; remoteCursorX = -1; remoteCursorY = -1; currentFps = 0; frameCount.set(0); fpsWindowStart = System.nanoTime(); setCursor(Cursor.getDefaultCursor()); repaint(); }
        double fps() {
            long now = System.nanoTime();
            if (now - fpsWindowStart >= TimeUnit.SECONDS.toNanos(1)) {
                long frames = frameCount.getAndSet(0);
                currentFps = frames / ((now - fpsWindowStart) / 1_000_000_000.0);
                fpsWindowStart = now;
            }
            return currentFps;
        }
        static Rectangle fitImageRectangle(int panelWidth, int panelHeight, int imageWidth, int imageHeight) {
            if (panelWidth <= 0 || panelHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null;
            double scale = Math.min(panelWidth / (double) imageWidth, panelHeight / (double) imageHeight);
            int width = Math.max(1, (int) Math.round(imageWidth * scale));
            int height = Math.max(1, (int) Math.round(imageHeight * scale));
            return new Rectangle((panelWidth - width) / 2, (panelHeight - height) / 2, width, height);
        }

        Rectangle getImageRectangle() {
            BufferedImage image = frame;
            if (image == null) return null;
            return fitImageRectangle(getWidth(), getHeight(), image.getWidth(), image.getHeight());
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(Color.BLACK); g.fillRect(0, 0, getWidth(), getHeight());
                BufferedImage image = frame;
                if (image == null) {
                    g.setColor(Color.WHITE); g.setFont(getFont().deriveFont(Font.PLAIN, 15f));
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(statusText, Math.max(12, (getWidth() - fm.stringWidth(statusText)) / 2), getHeight() / 2);
                } else {
                    Rectangle r = getImageRectangle();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(image, r.x, r.y, r.width, r.height, null);
                    int cx = remoteCursorX, cy = remoteCursorY;
                    if (cx >= 0 && cy >= 0) {
                        int px = r.x + (int) Math.round(cx / 65535.0 * Math.max(0, r.width - 1));
                        int py = r.y + (int) Math.round(cy / 65535.0 * Math.max(0, r.height - 1));
                        Path2D pointer = new Path2D.Double();
                        pointer.moveTo(px, py); pointer.lineTo(px, py + 19); pointer.lineTo(px + 5, py + 14);
                        pointer.lineTo(px + 10, py + 25); pointer.lineTo(px + 14, py + 23);
                        pointer.lineTo(px + 9, py + 12); pointer.lineTo(px + 17, py + 12); pointer.closePath();
                        g.setColor(new Color(0, 0, 0, 210));
                        Graphics2D shadow = (Graphics2D) g.create(); shadow.translate(1, 1); shadow.fill(pointer); shadow.dispose();
                        g.setColor(Color.WHITE); g.fill(pointer); g.setColor(new Color(20, 28, 40)); g.setStroke(new BasicStroke(1.2f)); g.draw(pointer);
                    }
                }
            } finally { g.dispose(); }
        }
    }
}
