package dev.pi.android.ssh;

import androidx.annotation.NonNull;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;

/** 应用内生成 Ed25519（失败则回退 RSA 2048）密钥对。 */
public final class SshKeyGen {
    public static final class Pair {
        @NonNull public final String privatePem;
        @NonNull public final String publicOpenSsh;

        public Pair(@NonNull String privatePem, @NonNull String publicOpenSsh) {
            this.privatePem = privatePem;
            this.publicOpenSsh = publicOpenSsh;
        }
    }

    private SshKeyGen() {}

    @NonNull
    public static Pair generate(@NonNull String comment) throws Exception {
        JSch jsch = new JSch();
        KeyPair kp;
        try {
            kp = KeyPair.genKeyPair(jsch, KeyPair.ED25519);
        } catch (Exception e) {
            kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048);
        }
        ByteArrayOutputStream priv = new ByteArrayOutputStream();
        ByteArrayOutputStream pub = new ByteArrayOutputStream();
        kp.writePrivateKey(priv);
        kp.writePublicKey(pub, comment == null ? "pi-android" : comment);
        kp.dispose();
        return new Pair(priv.toString("UTF-8"), pub.toString("UTF-8").trim());
    }
}
