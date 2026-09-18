package dev.airtv.airdrop;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Streaming, bounded cpio extraction into a fresh private staging directory. GPL-3.0. */
public final class CpioReceiver {
    private static final long DISK_RESERVE = 16L * 1024 * 1024;
    public static final int MAX_ENTRIES = 512;

    public static String safePath(String name) throws IOException {
        if (name.isEmpty() || name.length() > 4096 || name.startsWith("/") ||
                name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) throw new IOException("Unsafe archive path");
        StringBuilder result = new StringBuilder();
        for (String component : name.split("/", -1)) {
            if (component.equals(".")) continue;
            if (component.isEmpty() || component.equals("..") || component.length() > 255)
                throw new IOException("Unsafe archive path component");
            if (result.length() > 0) result.append('/');
            result.append(component);
        }
        if (result.length() == 0) throw new IOException("Empty archive path");
        return result.toString();
    }

    public static List<File> extract(InputStream wire, File root,
            Map<String, Boolean> approvedRoots) throws IOException {
        PushbackInputStream peek = new PushbackInputStream(wire, 2);
        byte[] first = exact(peek, 2); peek.unread(first);
        InputStream unpacked = (first[0] == 0x1f && first[1] == (byte) 0x8b) ? new GZIPInputStream(peek) : peek;
        Counted in = new Counted(unpacked);
        List<File> files = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        long total = 0;
        boolean trailer = false;
        for (int count = 0; count <= MAX_ENTRIES; count++) {
            String magic = new String(exact(in, 6), StandardCharsets.US_ASCII);
            long mode, links, size, nameSize, expectedChecksum = 0;
            boolean aligned = magic.equals("070701") || magic.equals("070702");
            if (aligned) {
                byte[] header = exact(in, 104);
                mode = numeric(header, 8, 8, 16);
                links = numeric(header, 32, 8, 16);
                size = numeric(header, 48, 8, 16);
                nameSize = numeric(header, 88, 8, 16);
                expectedChecksum = numeric(header, 96, 8, 16);
            } else if (magic.equals("070707")) {
                byte[] header = exact(in, 70);
                mode = numeric(header, 12, 6, 8);
                links = numeric(header, 30, 6, 8);
                nameSize = numeric(header, 53, 6, 8);
                size = numeric(header, 59, 11, 8);
            } else throw new IOException("Unsupported cpio format");
            if (nameSize < 1 || nameSize > 4097 || size > Math.max(0, root.getUsableSpace() - DISK_RESERVE) || total > Long.MAX_VALUE - size)
                throw new IOException("Archive limits exceeded");
            byte[] nameBytes = exact(in, (int) nameSize);
            if (nameBytes[nameBytes.length - 1] != 0) throw new IOException("Invalid cpio name");
            String name;
            try {
                name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(nameBytes, 0, nameBytes.length - 1)).toString();
            } catch (CharacterCodingException e) { throw new IOException("Invalid filename encoding", e); }
            if (aligned) padding(in);
            if (name.equals("TRAILER!!!")) {
                if (size != 0) throw new IOException("Invalid cpio trailer");
                trailer = true; break;
            }
            if (count == MAX_ENTRIES) throw new IOException("Too many archive entries");
            String path = safePath(name);
            String top = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
            Boolean isDirectory = approvedRoots.get(top);
            if (isDirectory == null || (!isDirectory && !top.equals(path)))
                throw new IOException("Archive contains unapproved path");
            if (!seen.add(path)) throw new IOException("Duplicate archive entry");
            int type = (int) mode & 0170000;
            if ((type != 0100000 && type != 0040000) ||
                    (type == 0100000 && links > 1)) throw new IOException("Links and special files are unsupported");
            if (path.equals(top) && isDirectory != (type == 0040000))
                throw new IOException("Archive type differs from approved offer");
            File target = new File(root, path);
            String prefix = root.getCanonicalPath() + File.separator;
            if (!target.getCanonicalPath().startsWith(prefix)) throw new IOException("Archive path escapes staging directory");
            if (type == 0040000) {
                if (size != 0) throw new IOException("Directory with data");
                if (!target.mkdirs() && !target.isDirectory()) throw new IOException("Cannot create directory");
            } else {
                File parent = target.getParentFile();
                if (!parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create parent directory");
                if (!target.createNewFile()) throw new IOException("Archive target already exists");
                long checksum = 0, remaining = size;
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    byte[] buffer = new byte[64 * 1024];
                    while (remaining > 0) {
                        int got = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (got < 0) throw new EOFException("Truncated archive file");
                        out.write(buffer, 0, got); remaining -= got;
                        if (magic.equals("070702")) for (int i = 0; i < got; i++) checksum = (checksum + (buffer[i] & 255)) & 0xffffffffL;
                    }
                }
                if (magic.equals("070702") && checksum != expectedChecksum) throw new IOException("Archive checksum mismatch");
                files.add(target);
            }
            total += size;
            if (aligned) padding(in);
        }
        if (!trailer || files.isEmpty()) throw new IOException("Empty or incomplete cpio archive");
        // Finish gzip CRC validation and consume HTTP framing. Only zero padding is valid.
        for (int padding = 0; ; padding++) {
            int next = in.read();
            if (next < 0) break;
            if (next != 0 || padding >= 16384) throw new IOException("Unexpected archive suffix");
        }
        for (String name : approvedRoots.keySet()) {
            if (!new File(root, name).exists()) throw new IOException("Approved file missing from archive");
        }
        return files;
    }

    private static long numeric(byte[] bytes, int pos, int length, int radix) throws IOException {
        String text = new String(bytes, pos, length, StandardCharsets.US_ASCII);
        if (!text.matches(radix == 8 ? "[0-7]+" : "[0-9a-fA-F]+")) throw new IOException("Invalid cpio number");
        try { return Long.parseLong(text, radix); }
        catch (NumberFormatException e) { throw new IOException("Cpio number overflow", e); }
    }

    static byte[] exact(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        int at = 0;
        while (at < count) { int read = in.read(bytes, at, count - at); if (read < 0) throw new EOFException(); at += read; }
        return bytes;
    }

    private static void padding(Counted in) throws IOException {
        while (in.count % 4 != 0) if (in.read() != 0) throw new IOException("Invalid archive alignment");
    }

    private static final class Counted extends FilterInputStream {
        long count;
        Counted(InputStream in) { super(in); }
        @Override public int read() throws IOException { int result = in.read(); if (result >= 0) count++; return result; }
        @Override public int read(byte[] b, int o, int n) throws IOException {
            int read = in.read(b, o, n); if (read > 0) count += read; return read;
        }
    }
}
