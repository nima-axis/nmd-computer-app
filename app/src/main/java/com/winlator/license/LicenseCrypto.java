package com.winlator.license;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Verifies that a license really was signed by YOUR server (ECDSA P-256 / SHA-256). Pure Java. */
public final class LicenseCrypto {
    private LicenseCrypto() {}

    public static boolean verify(String publicKeyB64, String payload, String sigB64) {
        try {
            PublicKey key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(key);
            s.update(payload.getBytes("UTF-8"));
            return s.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean verify(String payload, String sigB64) {
        return verify(LicenseConfig.PUBLIC_KEY_B64, payload, sigB64);
    }
}
