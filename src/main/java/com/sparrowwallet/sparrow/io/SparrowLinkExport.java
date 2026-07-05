package com.sparrowwallet.sparrow.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.crypto.ECIESKeyCrypter;
import com.sparrowwallet.drongo.crypto.ECKey;
import com.sparrowwallet.drongo.crypto.EncryptedData;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.DeterministicSeed;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;

/**
 * Exports an open wallet as an SPRW1/JSON file for Sparrow Link — the format
 * Sparrow-LTC Mobile reads (and older desktop versions wrote).
 *
 * The JSON is built explicitly rather than via JsonPersistence's Gson reflection:
 * modern Wallet/Keystore objects carry BouncyCastle-backed fields (MWEB keys etc.)
 * that Gson cannot reflect over under JPMS, and mobile only needs the seed wallet
 * essentials. The wallet's own key deriver and encryption key are reused, so the
 * password on the phone is unchanged, and the encrypted seed bytes are copied
 * verbatim from the in-memory (encrypted) wallet — no secrets are re-derived here.
 */
public class SparrowLinkExport {
    public static byte[] exportJsonWallet(Wallet wallet) throws ExportException {
        Wallet exportedWallet = !wallet.isMasterWallet() ? wallet.getMasterWallet() : wallet;
        Storage storage = AppServices.get().getOpenWallets().get(exportedWallet);
        if(storage == null) {
            throw new ExportException("Could not find the open wallet's storage");
        }
        return exportJsonWallet(exportedWallet, storage);
    }

    public static byte[] exportJsonWallet(Wallet exportedWallet, Storage storage) throws ExportException {
        if(exportedWallet.getPolicyType() != PolicyType.SINGLE || exportedWallet.getKeystores().size() != 1) {
            throw new ExportException("Only single-signature wallets can be pushed to mobile");
        }
        if(exportedWallet.getScriptType() != ScriptType.P2WPKH && exportedWallet.getScriptType() != ScriptType.MWEB) {
            throw new ExportException("Mobile currently supports Native Segwit (P2WPKH) and MWEB wallets — this wallet is "
                    + exportedWallet.getScriptType().getName());
        }
        if(exportedWallet.getNetwork() != Network.MAINNET && exportedWallet.getNetwork() != Network.TESTNET) {
            throw new ExportException("Mobile supports mainnet and testnet wallets — this wallet is on "
                    + exportedWallet.getNetwork().toDisplayString());
        }
        Keystore keystore = exportedWallet.getKeystores().get(0);
        if(!keystore.hasSeed()) {
            throw new ExportException("This wallet has no seed on this computer (watch-only or hardware wallet)");
        }
        if(keystore.getKeyDerivation() == null || keystore.getExtendedPublicKey() == null) {
            throw new ExportException("The wallet's keystore is missing its derivation or extended public key");
        }

        byte[] json = walletJson(exportedWallet, keystore).toString().getBytes(StandardCharsets.UTF_8);

        ECKey encryptionPubKey = storage.getEncryptionPubKey();
        if(encryptionPubKey == null || Storage.NO_PASSWORD_KEY.equals(encryptionPubKey)) {
            return json; // no-password wallet: plain JSON; the phone sets its own password
        }

        try {
            byte[] blob = new ECIESKeyCrypter().encryptEcies(encryptionPubKey, deflate(json),
                    "BIE1".getBytes(StandardCharsets.UTF_8));
            byte[] headerPayload = new byte[5 + storage.getKeyDeriver().getSalt().length];
            System.arraycopy("SPRW1".getBytes(StandardCharsets.UTF_8), 0, headerPayload, 0, 5);
            System.arraycopy(storage.getKeyDeriver().getSalt(), 0, headerPayload, 5, storage.getKeyDeriver().getSalt().length);
            byte[] header = Base64.getEncoder().encode(headerPayload);

            byte[] file = new byte[header.length + blob.length];
            System.arraycopy(header, 0, file, 0, header.length);
            System.arraycopy(blob, 0, file, header.length, blob.length);
            return file;
        } catch(Exception e) {
            throw new ExportException("Could not encrypt the wallet for transfer", e);
        }
    }

    private static JsonObject walletJson(Wallet wallet, Keystore keystore) throws ExportException {
        JsonObject json = new JsonObject();
        json.addProperty("name", wallet.getName());
        json.addProperty("network", wallet.getNetwork().name());
        json.addProperty("policyType", wallet.getPolicyType().name());
        json.addProperty("scriptType", wallet.getScriptType().name());

        JsonObject miniscript = new JsonObject();
        miniscript.addProperty("script", wallet.getDefaultPolicy().getMiniscript().getScript());
        JsonObject policy = new JsonObject();
        policy.addProperty("name", wallet.getDefaultPolicy().getName());
        policy.add("miniscript", miniscript);
        json.add("defaultPolicy", policy);

        JsonObject keyDerivation = new JsonObject();
        keyDerivation.addProperty("masterFingerprint", keystore.getKeyDerivation().getMasterFingerprint());
        keyDerivation.addProperty("derivationPath", keystore.getKeyDerivation().getDerivationPath());

        JsonObject keystoreObj = new JsonObject();
        keystoreObj.addProperty("label", keystore.getLabel());
        keystoreObj.addProperty("source", keystore.getSource().name());
        keystoreObj.addProperty("walletModel", keystore.getWalletModel().name());
        keystoreObj.add("keyDerivation", keyDerivation);
        keystoreObj.addProperty("extendedPublicKey", keystore.getExtendedPublicKey().toString());
        keystoreObj.add("seed", seedJson(keystore.getSeed()));

        JsonArray keystores = new JsonArray();
        keystores.add(keystoreObj);
        json.add("keystores", keystores);

        if(wallet.getScriptType() == ScriptType.MWEB) {
            json.add("mwebHistory", mwebHistory(wallet));
        }
        json.add("txLabels", txLabels(wallet));
        return json;
    }

    /**
     * The wallet's transaction labels (txid → label). Like MWEB history, labels are
     * desktop-side records that can't be recovered from the network — they travel with
     * the wallet so mobile can show them on its own history. Skipped by desktop's loader.
     */
    private static JsonObject txLabels(Wallet wallet) {
        JsonObject labels = new JsonObject();
        for(java.util.Map.Entry<com.sparrowwallet.drongo.protocol.Sha256Hash, com.sparrowwallet.drongo.wallet.BlockTransaction> entry
                : wallet.getTransactions().entrySet()) {
            String label = entry.getValue().getLabel();
            if(label != null && !label.isEmpty()) {
                labels.addProperty(entry.getKey().toString(), label);
            }
        }
        return labels;
    }

    /**
     * The wallet's recorded MWEB transaction history as net-value entries. MWEB prunes
     * spent outputs from the chain, so a freshly scanning device can only ever see current
     * coins — the desktop's records are the sole source of past activity, and travel with
     * the wallet. Ignored by desktop's own loader (unknown JSON keys are skipped).
     */
    private static JsonArray mwebHistory(Wallet wallet) {
        java.util.Map<String, long[]> deltas = new java.util.LinkedHashMap<>();     // txid → [value, height, time]
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        for(com.sparrowwallet.drongo.wallet.WalletNode purposeNode : wallet.getPurposeNodes()) {
            for(com.sparrowwallet.drongo.wallet.WalletNode node : purposeNode.getChildren()) {
                for(com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex txo : node.getTransactionOutputs()) {
                    accumulate(wallet, deltas, labels, txo.getHash().toString(), txo.getValue(),
                            txo.getHeight(), txo.getDate() == null ? 0 : txo.getDate().getTime() / 1000);
                    if(txo.isSpent()) {
                        com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex spender = txo.getSpentBy();
                        accumulate(wallet, deltas, labels, spender.getHash().toString(), -txo.getValue(),
                                spender.getHeight(), spender.getDate() == null ? 0 : spender.getDate().getTime() / 1000);
                    }
                }
            }
        }

        JsonArray history = new JsonArray();
        for(java.util.Map.Entry<String, long[]> entry : deltas.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("txid", entry.getKey());
            item.addProperty("value", entry.getValue()[0]);
            item.addProperty("height", entry.getValue()[1]);
            item.addProperty("time", entry.getValue()[2]);
            String label = labels.get(entry.getKey());
            if(label != null && !label.isEmpty()) {
                item.addProperty("label", label);
            }
            history.add(item);
        }
        return history;
    }

    private static void accumulate(Wallet wallet, java.util.Map<String, long[]> deltas,
            java.util.Map<String, String> labels, String txid, long value, long height, long time) {
        long[] entry = deltas.computeIfAbsent(txid, k -> new long[]{0, height, time});
        entry[0] += value;
        com.sparrowwallet.drongo.wallet.BlockTransaction blockTransaction =
                wallet.getTransactions().get(com.sparrowwallet.drongo.protocol.Sha256Hash.wrap(txid));
        if(blockTransaction != null && blockTransaction.getLabel() != null) {
            labels.put(txid, blockTransaction.getLabel());
        }
    }

    private static JsonObject seedJson(DeterministicSeed seed) throws ExportException {
        JsonObject seedObj = new JsonObject();
        seedObj.addProperty("type", seed.getType().name());
        if(seed.isEncrypted()) {
            EncryptedData data = seed.getEncryptedData();
            JsonObject encrypted = new JsonObject();
            encrypted.addProperty("initialisationVector", Utils.bytesToHex(data.getInitialisationVector()));
            encrypted.addProperty("encryptedBytes", Utils.bytesToHex(data.getEncryptedBytes()));
            if(data.getKeySalt() != null) {
                encrypted.addProperty("keySalt", Utils.bytesToHex(data.getKeySalt()));
            }
            JsonObject encryptionType = new JsonObject();
            encryptionType.addProperty("deriver", data.getEncryptionType().getDeriver().name());
            encryptionType.addProperty("crypter", data.getEncryptionType().getCrypter().name());
            encrypted.add("encryptionType", encryptionType);
            seedObj.add("encryptedMnemonicCode", encrypted);
        } else if(seed.getMnemonicCode() != null) {
            JsonArray words = new JsonArray();
            for(String word : seed.getMnemonicCode()) {
                words.add(word);
            }
            seedObj.add("mnemonicCode", words);
        } else {
            throw new ExportException("The wallet seed is unavailable");
        }
        seedObj.addProperty("needsPassphrase", seed.needsPassphrase());
        seedObj.addProperty("creationTimeSeconds", seed.getCreationTimeSeconds());
        return seedObj;
    }

    private static byte[] deflate(byte[] bytes) throws ExportException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try(DeflaterOutputStream deflaterStream = new DeflaterOutputStream(out)) {
                deflaterStream.write(bytes);
            }
            return out.toByteArray();
        } catch(IOException e) {
            throw new ExportException("Could not compress the wallet", e);
        }
    }
}
