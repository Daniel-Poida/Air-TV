package dev.airtv.airdrop;
import java.io.*;
import java.util.*;
public final class PlistMain {
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int got;
        while ((got = System.in.read(buffer)) >= 0) bytes.write(buffer, 0, got);
        Object value = BinaryPlist.decode(bytes.toByteArray());
        if (!(value instanceof Map)) throw new IOException("Dictionary expected");
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("ReceiverComputerName", "Тест Air TV");
        response.put("ReceiverModelName", "Android TV");
        System.out.write(BinaryPlist.encode(response));
    }
}
