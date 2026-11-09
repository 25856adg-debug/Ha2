package com.example.screenwake;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class AesUtil {
    // 16字节密钥，前后端统一
    private static final String AES_KEY = "1234567890abcdef";

    public static String[] encrypt(String plainText) throws Exception {
        byte[] keyBytes = AES_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] ivBytes = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(ivBytes);

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

        byte[] encryptBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        String encBase64 = android.util.Base64.encodeToString(encryptBytes, android.util.Base64.NO_WRAP);
        String ivBase64 = android.util.Base64.encodeToString(ivBytes, android.util.Base64.NO_WRAP);
        return new String[]{encBase64, ivBase64};
    }
}



