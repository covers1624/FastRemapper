package net.covers1624.fastremap;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Created by covers1624 on 29/9/24.
 */
public class Hashing {

    public static String sha256(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException("Unable to get SHA-256 digest.", ex);
        }
        digest.update(bytes);

        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            sb.append(Character.forDigit(b >> 4 & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
