package app.morphe.extension.instagram.patches.dm.vault;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** The vault key-encryption key: a non-exportable Android Keystore AES key, created on first use. */
public final class VaultKeys implements VaultFile.Kek {
    public static final VaultKeys INSTANCE = new VaultKeys();
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "piko_vault_key";

    private SecretKey key;

    private VaultKeys() {
    }

    @Override
    public synchronized SecretKey get() throws GeneralSecurityException {
        if (key != null) return key;
        KeyStore store = KeyStore.getInstance(PROVIDER);
        try {
            store.load(null);
        } catch (IOException e) {
            throw new KeyStoreException(e);
        }
        Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return key = (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        // No user authentication and no unlocked-device requirement, so capture works while locked.
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return key = generator.generateKey();
    }
}
