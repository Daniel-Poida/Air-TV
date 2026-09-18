package dev.airtv.airdrop;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Experimental AirDrop application layer. Does not implement BLE/AWDL. GPL-3.0. */
public final class AirDropReceiver implements Closeable {
    public interface Approval {
        boolean confirm(String unverifiedSenderName, Map<String, Boolean> offeredFiles) throws Exception;
    }
    public interface Received { void complete(File transferDirectory); }
    public interface Observer {
        void request(String protocolPath);
        default void transportError(Exception error) { }
    }
    private final Observer observer;
    private final SSLServerSocket listener;
    private final String receiverName;
    private final File storage;
    private final Approval approval;
    private final Received received;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(8));
    private final ConcurrentMap<String, Offer> offers = new ConcurrentHashMap<String, Offer>();
    private final Set<String> asking = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final ThreadPoolExecutor approvals = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(8));
    private final Set<SSLSocket> connections = Collections.newSetFromMap(new ConcurrentHashMap<SSLSocket, Boolean>());
    private volatile boolean closed;
    private Thread acceptThread;

    public AirDropReceiver(SSLContext tls, InetAddress address, int port, String name,
            File storage, Approval approval, Received received) throws IOException {
        this(tls, address, port, name, storage, approval, received, path -> {});
    }

    public AirDropReceiver(SSLContext tls, InetAddress address, int port, String name,
            File storage, Approval approval, Received received, Observer observer) throws IOException {
        this.receiverName = name; this.storage = storage;
        this.approval = approval; this.received = received;
        this.observer = observer;
        if (!storage.mkdirs() && !storage.isDirectory()) throw new IOException("Cannot create receiver storage");
        listener = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(port, 8, address);
        // AirDrop peers present self-signed certificates. The TLS context supplied
        // by the host must validate proof of possession, not require a public CA.
        // Offers are bound to the peer's certificate fingerprint, never to its IP.
        listener.setNeedClientAuth(true);
        List<String> protocols = new ArrayList<String>();
        for (String protocol : listener.getSupportedProtocols())
            if (protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3")) protocols.add(protocol);
        listener.setEnabledProtocols(protocols.toArray(new String[0]));
    }

    public int port() { return listener.getLocalPort(); }

    public synchronized void start() {
        if (acceptThread != null) throw new IllegalStateException("Receiver already started");
        acceptThread = new Thread(new Runnable() { public void run() {
            while (!closed) {
                try {
                    final SSLSocket socket = (SSLSocket) listener.accept();
                    connections.add(socket);
                    try { workers.execute(new Runnable() { public void run() { handle(socket); } }); }
                    catch (RejectedExecutionException e) { connections.remove(socket); socket.close(); }
                } catch (IOException e) { if (!closed) close(); }
            }
        } }, "AirDrop-accept");
        acceptThread.setDaemon(true); acceptThread.start();
    }

    private void handle(SSLSocket socket) {
        File staging = null;
        try (SSLSocket connection = socket) {
            connection.setSoTimeout(60000); connection.startHandshake();
            byte[] fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(connection.getSession().getPeerCertificates()[0].getEncoded());
            StringBuilder identity = new StringBuilder();
            for (byte value : fingerprint) identity.append(String.format(Locale.ROOT, "%02x", value & 255));
            String peer = identity.toString();
            InputStream input = new BufferedInputStream(connection.getInputStream());
            OutputStream output = new BufferedOutputStream(connection.getOutputStream());
            try {
                Request request = new Request(input);
                if (request.path.equals("/Discover") || request.path.equals("/Ask") || request.path.equals("/Upload"))
                    try { observer.request(request.path); } catch (RuntimeException ignored) { }
                if (!request.method.equals("POST")) { reply(output, 405, new byte[0]); return; }
                if (request.path.equals("/Discover")) {
                    dictionary(readSmall(request.body));
                    Map<String, Object> response = response();
                    response.put("ReceiverMediaCapabilities", "{\"Version\":1}".getBytes(StandardCharsets.UTF_8));
                    reply(output, 200, BinaryPlist.encode(response));
                } else if (request.path.equals("/Ask")) {
                    if (!asking.add(peer)) { reply(output, 503, new byte[0]); return; }
                    try {
                    Map<?, ?> value = dictionary(readSmall(request.body));
                    Object raw = value.get("Files");
                    if (!(raw instanceof List) || ((List<?>) raw).isEmpty() || ((List<?>) raw).size() > 128)
                        throw new IOException("Invalid AirDrop file offer");
                    Map<String, Boolean> files = new LinkedHashMap<String, Boolean>();
                    for (Object item : (List<?>) raw) {
                        if (!(item instanceof Map)) throw new IOException("Invalid offered file");
                        Map<?, ?> file = (Map<?, ?>) item;
                        Object rawName = file.get("FileName"), rawDirectory = file.get("FileIsDirectory");
                        if (!(rawName instanceof String) || !(rawDirectory instanceof Boolean))
                            throw new IOException("Invalid offered file fields");
                        String name = CpioReceiver.safePath((String) rawName);
                        if (name.contains("/") || files.put(name, (Boolean) rawDirectory) != null)
                            throw new IOException("Invalid offered filename");
                    }
                    String sender = value.get("SenderComputerName") instanceof String ?
                        (String) value.get("SenderComputerName") : "Unknown device";
                    if (sender.length() > 200) throw new IOException("Oversized sender name");
                    offers.remove(peer);
                    long now = System.nanoTime();
                    for (Map.Entry<String, Offer> entry : offers.entrySet())
                        if (entry.getValue().expired(now)) offers.remove(entry.getKey(), entry.getValue());
                    if (offers.size() >= 16) { reply(output, 503, new byte[0]); return; }
                    final String senderName = sender;
                    final Map<String, Boolean> offeredFiles = Collections.unmodifiableMap(files);
                    Future<Boolean> decision = approvals.submit(new Callable<Boolean>() {
                        public Boolean call() throws Exception { return approval.confirm(senderName, offeredFiles); }
                    });
                    boolean accepted;
                    try { accepted = decision.get(45, TimeUnit.SECONDS); }
                    finally { decision.cancel(true); approvals.purge(); }
                    if (!accepted) {
                        reply(output, 403, new byte[0]); return;
                    }
                    offers.put(peer, new Offer(files));
                    reply(output, 200, BinaryPlist.encode(response()));
                    } finally { asking.remove(peer); }
                } else if (request.path.equals("/Upload")) {
                    Offer offer = offers.remove(peer);
                    if (offer == null || offer.expired(System.nanoTime())) { reply(output, 403, new byte[0]); return; }
                    if (!"application/x-cpio".equalsIgnoreCase(request.headers.get("content-type"))) {
                        reply(output, 415, new byte[0]); return;
                    }
                    if (request.expectContinue) { output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); output.flush(); }
                    staging = new File(storage, UUID.randomUUID().toString() + ".partial");
                    if (!staging.mkdir()) throw new IOException("Cannot create transfer staging directory");
                    CpioReceiver.extract(request.body, staging, offer.files);
                    File committed = new File(storage, staging.getName().replace(".partial", ""));
                    if (!staging.renameTo(committed)) throw new IOException("Cannot commit transfer");
                    staging = null;
                    // A host UI failure cannot undo an already committed transfer.
                    try { received.complete(committed); } catch (RuntimeException ignored) { }
                    reply(output, 200, new byte[0]);
                } else reply(output, 404, new byte[0]);
            } catch (Exception e) {
                if (staging != null) { removeStaging(staging); staging = null; }
                reply(output, 400, new byte[0]);
            }
        } catch (Exception e) {
            // An invalid handshake or a broken transfer must not terminate the listener.
            try { observer.transportError(e); } catch (RuntimeException ignored) { }
        } finally { connections.remove(socket); if (staging != null) removeStaging(staging); }
    }

    private Map<String, Object> response() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ReceiverComputerName", receiverName); result.put("ReceiverModelName", "Android TV");
        return result;
    }

    private static Map<?, ?> dictionary(byte[] bytes) throws IOException {
        Object value = BinaryPlist.decode(bytes);
        if (!(value instanceof Map)) throw new IOException("AirDrop plist is not a dictionary");
        return (Map<?, ?>) value;
    }

    private static byte[] readSmall(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int got;
        while ((got = in.read(buffer)) >= 0) {
            if (bytes.size() + got > 1024 * 1024) throw new IOException("AirDrop plist too large");
            bytes.write(buffer, 0, got);
        }
        return bytes.toByteArray();
    }

    private static void reply(OutputStream out, int status, byte[] body) throws IOException {
        out.write(("HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Rejected") +
            "\r\nContent-Length: " + body.length + "\r\nContent-Type: application/octet-stream" +
            "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body); out.flush();
    }

    private static void removeStaging(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeStaging(child);
        file.delete();
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        for (SSLSocket socket : connections) try { socket.close(); } catch (IOException ignored) { }
        offers.clear(); asking.clear(); workers.shutdownNow(); approvals.shutdownNow();
    }

    private static final class Offer {
        final Map<String, Boolean> files;
        final long accepted = System.nanoTime();
        Offer(Map<String, Boolean> files) { this.files = new LinkedHashMap<String, Boolean>(files); }
        boolean expired(long now) { return now - accepted > TimeUnit.MINUTES.toNanos(2); }
    }

    private static String line(InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() <= max + 1) {
            int next = input.read(); if (next < 0) throw new EOFException();
            if (next == '\n') {
                byte[] bytes = out.toByteArray();
                if (bytes.length < 1 || bytes.length > max + 1 || bytes[bytes.length - 1] != '\r') throw new IOException("Invalid HTTP line ending");
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            if (next > 127 || next == 0) throw new IOException("Invalid HTTP header byte");
            out.write(next);
        }
        throw new IOException("HTTP line too long");
    }

    private static final class Request {
        final String method, path;
        final Map<String, String> headers = new HashMap<String, String>();
        final InputStream body;
        final boolean expectContinue;
        Request(InputStream in) throws IOException {
            String[] request = line(in, 4096).split(" ");
            if (request.length != 3 || !request[2].equals("HTTP/1.1")) throw new IOException("Invalid HTTP request");
            method = request[0]; path = request[1];
            int total = 0, count = 0;
            for (;;) {
                String field = line(in, 4096); total += field.length() + 2;
                if (total > 16384 || ++count > 40) throw new IOException("HTTP headers too large");
                if (field.isEmpty()) break;
                int colon = field.indexOf(':');
                if (colon < 1) throw new IOException("Invalid HTTP header");
                String name = field.substring(0, colon).toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9-]+") || headers.put(name, field.substring(colon + 1).trim()) != null)
                    throw new IOException("Duplicate or invalid HTTP header");
            }
            String encoding = headers.get("transfer-encoding"), length = headers.get("content-length");
            if (encoding != null && length != null) throw new IOException("Ambiguous HTTP framing");
            if (headers.containsKey("content-encoding")) throw new IOException("Unsupported HTTP content encoding");
            expectContinue = "100-continue".equalsIgnoreCase(headers.get("expect"));
            if (headers.containsKey("expect") && !expectContinue) throw new IOException("Unsupported HTTP expectation");
            if (expectContinue && !path.equals("/Upload")) throw new IOException("Unsupported HTTP expectation");
            if (encoding != null) {
                if (!encoding.equalsIgnoreCase("chunked")) throw new IOException("Unsupported transfer encoding");
                body = new Chunked(in);
            } else {
                if (length == null || !length.matches("[0-9]{1,19}")) throw new IOException("Missing content length");
                long size = Long.parseLong(length);
                if (!path.equals("/Upload") && size > 1024 * 1024) throw new IOException("Control body too large");
                body = new Fixed(in, size);
            }
        }
    }

    private static final class Fixed extends FilterInputStream {
        long remaining;
        Fixed(InputStream input, long count) { super(input); remaining = count; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int size) throws IOException {
            if (size == 0) return 0;
            if (remaining == 0) return -1;
            int got = in.read(bytes, offset, (int) Math.min(size, remaining));
            if (got < 0) throw new EOFException("Incomplete HTTP body");
            remaining -= got; return got;
        }
    }

    private static final class Chunked extends FilterInputStream {
        long remaining, total;
        boolean first = true, finished;
        Chunked(InputStream in) { super(in); }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] bytes, int offset, int size) throws IOException {
            if (size == 0) return 0;
            if (finished) return -1;
            if (remaining == 0) {
                if (!first && !line(in, 0).isEmpty()) throw new IOException("Invalid chunk ending");
                first = false;
                String chunk = line(in, 128).split(";", 2)[0];
                if (!chunk.matches("[0-9a-fA-F]{1,8}")) throw new IOException("Invalid HTTP chunk");
                remaining = Long.parseLong(chunk, 16);
                if (remaining > Long.MAX_VALUE - total) throw new IOException("HTTP body limit exceeded");
                if (remaining == 0) {
                    // AirDrop doesn't need trailers; reject them to keep framing unambiguous.
                    if (!line(in, 0).isEmpty()) throw new IOException("HTTP trailers unsupported");
                    finished = true; return -1;
                }
            }
            int got = in.read(bytes, offset, (int) Math.min(size, remaining));
            if (got < 0) throw new EOFException("Truncated HTTP chunk");
            remaining -= got; total += got; return got;
        }
    }
}
