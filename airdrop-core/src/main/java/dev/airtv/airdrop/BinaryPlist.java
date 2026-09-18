package dev.airtv.airdrop;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded binary plist codec for the AirDrop application protocol. GPL-3.0. */
public final class BinaryPlist {
    private static final int MAX_OBJECTS = 4096, MAX_BYTES = 1024 * 1024;
    private final byte[] bytes;
    private final int referenceSize;
    private final long[] offsets;
    private final long tableStart;
    private final Object[] decoded;
    private final boolean[] complete;
    private final Set<Integer> visiting = new HashSet<Integer>();
    private int budget = 16384;

    private BinaryPlist(byte[] data) throws IOException {
        if (data.length < 40 || data.length > MAX_BYTES ||
                !Arrays.equals(Arrays.copyOf(data, 8), "bplist00".getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Unsupported or oversized plist");
        bytes = data;
        int trailer = data.length - 32;
        int offsetSize = data[trailer + 6] & 255;
        referenceSize = data[trailer + 7] & 255;
        long count = number(trailer + 8, 8, data.length);
        tableStart = number(trailer + 24, 8, data.length);
        if (count < 1 || count > MAX_OBJECTS || offsetSize < 1 || offsetSize > 8 ||
                referenceSize < 1 || referenceSize > 4 || tableStart < 8 ||
                tableStart + count * offsetSize > trailer) throw new IOException("Invalid plist trailer");
        offsets = new long[(int) count];
        decoded = new Object[(int) count]; complete = new boolean[(int) count];
        for (int i = 0; i < count; i++) {
            offsets[i] = number(tableStart + i * offsetSize, offsetSize, trailer);
            if (offsets[i] < 8 || offsets[i] >= tableStart) throw new IOException("Invalid plist offset");
        }
    }

    public static Object decode(byte[] data) throws IOException {
        BinaryPlist reader = new BinaryPlist(data);
        long root = reader.number(data.length - 16, 8, data.length);
        return reader.object(reader.index(root), 0);
    }

    private int index(long value) throws IOException {
        if (value < 0 || value >= offsets.length) throw new IOException("Invalid plist reference");
        return (int) value;
    }

    private void range(long start, long size, long limit) throws IOException {
        if (start < 0 || size < 0 || start > limit || size > limit - start)
            throw new IOException("Truncated plist");
    }

    private long number(long pos, int size, long limit) throws IOException {
        range(pos, size, limit);
        long result = 0;
        for (int i = 0; i < size; i++) {
            if (result > (Long.MAX_VALUE >>> 8)) throw new IOException("Plist integer overflow");
            result = (result << 8) | (bytes[(int) pos + i] & 255);
        }
        return result;
    }

    private Object object(int index, int depth) throws IOException {
        if (depth > 32 || --budget < 0) throw new IOException("Plist nesting or reference limit");
        if (complete[index]) return decoded[index];
        if (!visiting.add(index)) throw new IOException("Plist cycle");
        try {
            Object value = readObject(index, depth);
            decoded[index] = value; complete[index] = true;
            return value;
        } finally { visiting.remove(index); }
    }

    private Object readObject(int index, int depth) throws IOException {
            int position = (int) offsets[index];
            int marker = bytes[position++] & 255;
            int type = marker >>> 4, info = marker & 15;
            if (type == 0) {
                if (info == 0) return null;
                if (info == 8 || info == 9) return info == 9;
                throw new IOException("Invalid simple plist value");
            }
            if (type == 1) {
                if (info > 3) throw new IOException("Oversized plist integer");
                return number(position, 1 << info, tableStart);
            }
            if (type == 2) {
                if (info == 2) return Float.intBitsToFloat((int) number(position, 4, tableStart));
                if (info == 3) {
                    range(position, 8, tableStart);
                    return ByteBuffer.wrap(bytes, position, 8).getDouble();
                }
                throw new IOException("Invalid plist real");
            }
            long length = info;
            if (info == 15) {
                range(position, 1, tableStart);
                int lengthMarker = bytes[position++] & 255;
                if ((lengthMarker >>> 4) != 1 || (lengthMarker & 15) > 3)
                    throw new IOException("Invalid plist length");
                int size = 1 << (lengthMarker & 15);
                length = number(position, size, tableStart);
                position += size;
            }
            if (length > MAX_BYTES) throw new IOException("Oversized plist value");
            if (type == 4) {
                range(position, length, tableStart);
                return Arrays.copyOfRange(bytes, position, position + (int) length);
            }
            if (type == 5 || type == 6) {
                int size = (int) length * (type == 6 ? 2 : 1);
                range(position, size, tableStart);
                try {
                    return (type == 6 ? StandardCharsets.UTF_16BE : StandardCharsets.US_ASCII)
                        .newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, position, size)).toString();
                } catch (CharacterCodingException e) { throw new IOException("Invalid plist string", e); }
            }
            if (type != 10 && type != 13) throw new IOException("Unsupported plist object");
            if (length > MAX_OBJECTS) throw new IOException("Oversized plist collection");
            range(position, length * referenceSize * (type == 13 ? 2 : 1), tableStart);
            if (type == 10) {
                List<Object> result = new ArrayList<Object>();
                for (int i = 0; i < length; i++) result.add(object(index(number(
                    position + i * referenceSize, referenceSize, tableStart)), depth + 1));
                return result;
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (int i = 0; i < length; i++) {
                Object key = object(index(number(position + i * referenceSize, referenceSize, tableStart)), depth + 1);
                if (!(key instanceof String) || result.containsKey(key)) throw new IOException("Invalid plist key");
                Object value = object(index(number(position + (length + i) * referenceSize,
                    referenceSize, tableStart)), depth + 1);
                result.put((String) key, value);
            }
            return result;
    }

    public static byte[] encode(Map<String, Object> dictionary) throws IOException {
        List<byte[]> objects = new ArrayList<byte[]>();
        List<Integer> keys = new ArrayList<Integer>(), values = new ArrayList<Integer>();
        for (Map.Entry<String, Object> item : dictionary.entrySet()) {
            keys.add(objects.size()); objects.add(value(item.getKey()));
            values.add(objects.size()); objects.add(value(item.getValue()));
        }
        if (objects.size() >= 255) throw new IOException("Response plist too large");
        ByteArrayOutputStream root = new ByteArrayOutputStream();
        sized(root, 13, dictionary.size());
        for (int reference : keys) root.write(reference);
        for (int reference : values) root.write(reference);
        int rootIndex = objects.size(); objects.add(root.toByteArray());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("bplist00".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<Integer>();
        for (byte[] item : objects) { offsets.add(output.size()); output.write(item); }
        int table = output.size();
        DataOutputStream data = new DataOutputStream(output);
        for (int offset : offsets) data.writeInt(offset);
        data.write(new byte[6]); data.writeByte(4); data.writeByte(1);
        data.writeLong(objects.size()); data.writeLong(rootIndex); data.writeLong(table);
        return output.toByteArray();
    }

    private static void sized(OutputStream out, int type, int size) throws IOException {
        if (size < 15) out.write((type << 4) | size);
        else { out.write((type << 4) | 15); out.write(0x12); new DataOutputStream(out).writeInt(size); }
    }

    private static byte[] value(Object value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (value instanceof String) {
            String text = (String) value;
            byte[] bytes = text.getBytes(StandardCharsets.UTF_16BE);
            sized(out, 6, bytes.length / 2); out.write(bytes);
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value; sized(out, 4, bytes.length); out.write(bytes);
        } else if (value instanceof Boolean) out.write((Boolean) value ? 9 : 8);
        else throw new IOException("Unsupported response plist value");
        return out.toByteArray();
    }
}
