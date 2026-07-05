package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.crypto.Argon2KeyDeriver;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.Key;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * LOCAL-ONLY test (not for commit): round-trips the exact bytes Sparrow Link pushes to
 * mobile — SparrowLinkExport writes the SPRW1/JSON file from an in-memory encrypted
 * wallet, and Storage must load it back completely. Also regenerates
 * mobile/vectors/desktop-link-vector-wallet for the mobile importer's parity test
 * (password "pass", Argon2 TEST_PARAMETERS as always under gradle test).
 */
public class SparrowLinkExportTest {
    private static final String MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @Test
    public void exportRoundTripsThroughDesktopLoad() throws Exception {
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, "", 0, DeterministicSeed.Type.BIP39);
        Wallet wallet = new Wallet("Link Test");
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.P2WPKH);
        Keystore keystore = Keystore.fromSeed(seed, ScriptType.P2WPKH.getDefaultDerivation());
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.P2WPKH, wallet.getKeystores(), null));
        Assertions.assertTrue(wallet.isValid());
        Assertions.assertEquals("73c5da0a", keystore.getKeyDerivation().getMasterFingerprint());

        Argon2KeyDeriver keyDeriver = new Argon2KeyDeriver();
        Key key = keyDeriver.deriveKey("pass");
        wallet.encrypt(key);

        File tempDir = Files.createTempDirectory("sparrow-link-export-test").toFile();
        File file = new File(tempDir, "Link Test");
        Storage storage = new Storage(file);
        storage.setKeyDeriver(keyDeriver);
        storage.setEncryptionPubKey(ECKey.fromPublicOnly(ECKey.fromPrivate(key.getKeyBytes())));

        byte[] exported = SparrowLinkExport.exportJsonWallet(wallet, storage);
        Files.write(file.toPath(), exported);
        // regenerate the vector the mobile importer test reads
        Files.write(new File("../mobile/vectors/desktop-link-vector-wallet").toPath(), exported);

        Storage loadStorage = new Storage(file);
        WalletAndKey walletAndKey = loadStorage.loadEncryptedWallet("pass");
        Wallet loaded = walletAndKey.getWallet();
        Assertions.assertEquals("Link Test", loaded.getName());
        Assertions.assertEquals(Network.MAINNET, loaded.getNetwork());
        Assertions.assertEquals(PolicyType.SINGLE, loaded.getPolicyType());
        Assertions.assertEquals(ScriptType.P2WPKH, loaded.getScriptType());
        Assertions.assertEquals(1, loaded.getDefaultPolicy().getNumSignaturesRequired());
        Assertions.assertEquals("m/84'/2'/0'", loaded.getKeystores().get(0).getKeyDerivation().getDerivationPath());

        Wallet copy = loaded.copy();
        copy.decrypt(walletAndKey.getKey());
        Assertions.assertEquals(MNEMONIC, copy.getKeystores().get(0).getSeed().getMnemonicString().asString());

        Keystore derived = Keystore.fromSeed(copy.getKeystores().get(0).getSeed(),
                copy.getKeystores().get(0).getKeyDerivation().getDerivation());
        Assertions.assertEquals("73c5da0a", derived.getKeyDerivation().getMasterFingerprint());
        Assertions.assertEquals(keystore.getExtendedPublicKey().toString(), derived.getExtendedPublicKey().toString());
    }

    @Test
    public void exportsSeparateSeedMwebWallet() throws Exception {
        // a different seed than the public wallet, as in a separate-seed WalletLink pairing
        String mwebMnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow";
        DeterministicSeed seed = new DeterministicSeed(mwebMnemonic, "", 0, DeterministicSeed.Type.BIP39);
        Wallet wallet = new Wallet("Link Private Test");
        wallet.setPolicyType(PolicyType.SINGLE);
        wallet.setScriptType(ScriptType.MWEB);
        Keystore keystore = Keystore.fromSeed(seed, ScriptType.MWEB.getDefaultDerivation());
        wallet.getKeystores().add(keystore);
        wallet.setDefaultPolicy(Policy.getPolicy(PolicyType.SINGLE, ScriptType.MWEB, wallet.getKeystores(), null));
        Assertions.assertTrue(wallet.isValid());
        Assertions.assertEquals("m/1000'", keystore.getKeyDerivation().getDerivationPath());

        Argon2KeyDeriver keyDeriver = new Argon2KeyDeriver();
        Key key = keyDeriver.deriveKey("pass");
        wallet.encrypt(key);

        File tempDir = Files.createTempDirectory("sparrow-link-mweb-test").toFile();
        File file = new File(tempDir, "Link Private Test");
        Storage storage = new Storage(file);
        storage.setKeyDeriver(keyDeriver);
        storage.setEncryptionPubKey(ECKey.fromPublicOnly(ECKey.fromPrivate(key.getKeyBytes())));

        byte[] exported = SparrowLinkExport.exportJsonWallet(wallet, storage);
        Files.write(file.toPath(), exported);
        // regenerate the vector the mobile importer test reads
        Files.write(new File("../mobile/vectors/desktop-link-mweb-vector-wallet").toPath(), exported);

        Storage loadStorage = new Storage(file);
        WalletAndKey walletAndKey = loadStorage.loadEncryptedWallet("pass");
        Wallet loaded = walletAndKey.getWallet();
        Assertions.assertEquals("Link Private Test", loaded.getName());
        Assertions.assertEquals(ScriptType.MWEB, loaded.getScriptType());
        Assertions.assertEquals("m/1000'", loaded.getKeystores().get(0).getKeyDerivation().getDerivationPath());

        Wallet copy = loaded.copy();
        copy.decrypt(walletAndKey.getKey());
        Assertions.assertEquals(mwebMnemonic, copy.getKeystores().get(0).getSeed().getMnemonicString().asString());
    }
}
