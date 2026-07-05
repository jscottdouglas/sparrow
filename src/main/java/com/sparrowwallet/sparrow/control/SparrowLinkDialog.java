package com.sparrowwallet.sparrow.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WalletEntryLabelsChangedEvent;
import com.sparrowwallet.sparrow.io.SparrowLinkExport;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.wallet.Entry;
import com.sparrowwallet.sparrow.wallet.TransactionEntry;
import com.sparrowwallet.sparrow.wallet.WalletForm;
import com.sparrowwallet.sparrow.wallet.WalletLink;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * "Sparrow Link": pushes the open wallet to a phone running Sparrow-LTC Mobile, or
 * receives a wallet from it, over a direct TCP connection paired by this QR code.
 * Works on a shared WiFi network or over USB tethering (which also covers an offline
 * desktop). Frames are AES-256-GCM sealed with the session key in the QR; the wallet
 * file itself additionally remains password-encrypted, so no password crosses the link.
 *
 * Wire format (mirrored by mobile's SparrowLink.kt):
 *   QR:    sparrowlink1;m=&lt;push|pull&gt;;h=&lt;ip[,ip...]&gt;;p=&lt;port&gt;;k=&lt;base64url key32&gt;
 *   frame: u32 length | nonce12 | ciphertext (AAD "SLNK1")
 *   push:  desktop sends {"op":"wallet","name":...,"file":base64} then reads {"ok":true}
 *   pull:  desktop reads the same message and replies with the ack.
 */
public class SparrowLinkDialog extends Dialog<ButtonType> {
    private static final Logger log = LoggerFactory.getLogger(SparrowLinkDialog.class);
    private static final byte[] AAD = "SLNK1".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_FRAME = 16 * 1024 * 1024;

    public enum Mode { PUSH, RECEIVE, LABELS }

    private final byte[] sessionKey = new byte[32];
    private final ServerSocket serverSocket;
    private final Label statusLabel = new Label();
    private final SecureRandom secureRandom = new SecureRandom();

    // LABELS mode: send data prebuilt on the FX thread, forms indexed for the apply phase
    private JsonObject labelsMessage;
    private final Map<String, List<WalletForm>> formsByFingerprint = new java.util.LinkedHashMap<>();

    public SparrowLinkDialog(Mode mode, Wallet wallet) throws IOException {
        this(mode, wallet, null);
    }

    /** Label-sync dialog: exchanges transaction labels with the phone for all given wallets. */
    public SparrowLinkDialog(List<WalletForm> labelWalletForms) throws IOException {
        this(Mode.LABELS, null, labelWalletForms);
    }

    private SparrowLinkDialog(Mode mode, Wallet wallet, List<WalletForm> labelWalletForms) throws IOException {
        secureRandom.nextBytes(sessionKey);
        serverSocket = new ServerSocket(0);

        if(mode == Mode.LABELS) {
            buildLabelsMessage(labelWalletForms);
        }

        final DialogPane dialogPane = getDialogPane();
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());
        setTitle(mode == Mode.PUSH ? "Push Wallet to Phone"
                : mode == Mode.RECEIVE ? "Receive Wallet from Phone" : "Sync Labels with Phone");

        String qrText = "sparrowlink1;m=" + (mode == Mode.PUSH ? "push" : mode == Mode.RECEIVE ? "pull" : "labels")
                + ";h=" + String.join(",", localAddresses())
                + ";p=" + serverSocket.getLocalPort()
                + ";k=" + Base64.getUrlEncoder().withoutPadding().encodeToString(sessionKey);

        VBox vBox = new VBox(15);
        vBox.setPadding(new Insets(20, 25, 10, 25));
        vBox.setAlignment(Pos.CENTER);

        String pushDescription = null;
        if(mode == Mode.PUSH) {
            Wallet linkedMweb = WalletLink.getMwebWallet(wallet);
            pushDescription = linkedMweb != null && linkedMweb.isMasterWallet() && linkedMweb != wallet
                    ? "\"" + wallet.getName() + "\" and its linked private wallet \"" + linkedMweb.getName() + "\""
                    : "\"" + wallet.getName() + "\"";
        }
        Label headerLabel = new Label(mode == Mode.PUSH
                ? "In Sparrow-LTC Mobile, tap \"Import wallet from desktop\" and scan this code to copy "
                    + pushDescription + " to your phone. The phone and this computer must share a network "
                    + "(same WiFi, or USB tethering from the phone)."
                : mode == Mode.RECEIVE
                ? "In Sparrow-LTC Mobile, tap \"Import wallet from desktop\", scan this code, and pick the "
                    + "wallet to send — it will be copied to this computer. The phone and this computer "
                    + "must share a network (same WiFi, or USB tethering from the phone)."
                : "In Sparrow-LTC Mobile, open the wallet and tap \"Sync labels with desktop\", then scan "
                    + "this code. Each side copies over the labels it's missing — labels that differ are "
                    + "left unchanged on both. The phone and this computer must share a network.");
        headerLabel.setWrapText(true);
        headerLabel.setMaxWidth(420);

        statusLabel.setText("Waiting for your phone to scan…");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(420);

        vBox.getChildren().addAll(headerLabel, new ImageView(qrImage(qrText)), statusLabel);
        dialogPane.setContent(vBox);
        dialogPane.getButtonTypes().add(new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialogPane.setPrefWidth(500);
        AppServices.moveToActiveWindowScreen(this);

        Thread serverThread = new Thread(() -> serve(mode, wallet), "sparrow-link");
        serverThread.setDaemon(true);
        serverThread.start();

        setOnCloseRequest(event -> closeSocket());
    }

    /** Snapshot of every open wallet's transaction labels, built on the FX thread. */
    private void buildLabelsMessage(List<WalletForm> walletForms) {
        labelsMessage = new JsonObject();
        labelsMessage.addProperty("op", "labels");
        JsonArray walletsArray = new JsonArray();
        for(WalletForm form : walletForms) {
            Wallet w = form.getWallet();
            if(w.getKeystores().isEmpty() || w.getKeystores().get(0).getKeyDerivation() == null) {
                continue;
            }
            String fingerprint = w.getKeystores().get(0).getKeyDerivation().getMasterFingerprint();
            if(fingerprint == null || fingerprint.isEmpty()) {
                continue;
            }
            formsByFingerprint.computeIfAbsent(fingerprint.toLowerCase(), f -> new ArrayList<>()).add(form);

            JsonObject entry = new JsonObject();
            entry.addProperty("name", w.getName());
            entry.addProperty("fingerprint", fingerprint.toLowerCase());
            JsonObject labels = new JsonObject();
            for(Entry txEntry : form.getWalletTransactionsEntry().getChildren()) {
                if(txEntry instanceof TransactionEntry transactionEntry) {
                    String label = transactionEntry.getBlockTransaction().getLabel();
                    if(label != null && !label.isEmpty()) {
                        labels.addProperty(transactionEntry.getBlockTransaction().getHashAsString(), label);
                    }
                }
            }
            entry.add("labels", labels);
            walletsArray.add(entry);
        }
        labelsMessage.add("wallets", walletsArray);
    }

    private void serveLabels(DataInputStream in, DataOutputStream out) throws Exception {
        writeFrame(out, labelsMessage.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject reply = JsonParser.parseString(new String(readFrame(in), StandardCharsets.UTF_8)).getAsJsonObject();
        if(!reply.has("op") || !"labels".equals(reply.get("op").getAsString())) {
            throw new IOException("Unexpected reply from phone");
        }

        // collect (forms, txid, label) off-thread; mutate entries and post events on the FX thread
        List<Object[]> applies = new ArrayList<>();
        for(JsonElement element : reply.getAsJsonArray("wallets")) {
            JsonObject walletObj = element.getAsJsonObject();
            String fingerprint = walletObj.get("fingerprint").getAsString().toLowerCase();
            List<WalletForm> forms = formsByFingerprint.get(fingerprint);
            if(forms == null) {
                continue;
            }
            JsonObject labels = walletObj.getAsJsonObject("labels");
            for(String txid : labels.keySet()) {
                applies.add(new Object[] { forms, txid, labels.get(txid).getAsString() });
            }
        }

        JsonObject ack = new JsonObject();
        ack.addProperty("ok", true);
        writeFrame(out, ack.toString().getBytes(StandardCharsets.UTF_8));

        Platform.runLater(() -> {
            int applied = 0;
            Map<Wallet, List<Entry>> changedEntries = new java.util.LinkedHashMap<>();
            for(Object[] apply : applies) {
                @SuppressWarnings("unchecked")
                List<WalletForm> forms = (List<WalletForm>)apply[0];
                String txid = (String)apply[1];
                String label = (String)apply[2];
                for(WalletForm form : forms) {
                    for(Entry entry : form.getWalletTransactionsEntry().getChildren()) {
                        if(entry instanceof TransactionEntry transactionEntry
                                && transactionEntry.getBlockTransaction().getHashAsString().equals(txid)) {
                            String current = transactionEntry.getBlockTransaction().getLabel();
                            if(current == null || current.isEmpty()) {
                                transactionEntry.getBlockTransaction().setLabel(label);
                                transactionEntry.labelProperty().set(label);
                                changedEntries.computeIfAbsent(form.getWallet(), w -> new ArrayList<>()).add(entry);
                                applied++;
                            }
                        }
                    }
                }
            }
            for(Map.Entry<Wallet, List<Entry>> walletEntries : changedEntries.entrySet()) {
                EventManager.get().post(new WalletEntryLabelsChangedEvent(walletEntries.getKey(), walletEntries.getValue(), false));
            }
            statusLabel.setText("Labels synced ✓ — adopted " + applied + " label(s) from the phone. "
                    + "The phone reports its own count.");
        });
    }

    private void serve(Mode mode, Wallet wallet) {
        try(Socket socket = serverSocket.accept()) {
            socket.setSoTimeout(60_000);
            Platform.runLater(() -> statusLabel.setText("Phone connected…"));
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            if(mode == Mode.LABELS) {
                serveLabels(in, out);
            } else if(mode == Mode.PUSH) {
                byte[] fileBytes = SparrowLinkExport.exportJsonWallet(wallet);
                JsonObject message = new JsonObject();
                message.addProperty("op", "wallet");
                message.addProperty("name", wallet.getName());
                message.addProperty("file", Base64.getEncoder().encodeToString(fileBytes));

                // a separately-seeded linked private (MWEB) wallet travels with its public wallet;
                // a same-seed child needs no transfer (one seed carries both sides)
                String linkNote = "";
                Wallet mwebWallet = WalletLink.getMwebWallet(wallet);
                if(mwebWallet != null && mwebWallet.isMasterWallet() && mwebWallet != wallet) {
                    try {
                        JsonObject linked = new JsonObject();
                        linked.addProperty("name", mwebWallet.getName());
                        linked.addProperty("file", Base64.getEncoder().encodeToString(SparrowLinkExport.exportJsonWallet(mwebWallet)));
                        message.add("linked", linked);
                        linkNote = " Sent with its linked private wallet \"" + mwebWallet.getName() + "\".";
                    } catch(Exception e) {
                        linkNote = " (Couldn't include the linked private wallet: " + e.getMessage() + ")";
                    }
                } else if(mwebWallet == null && wallet.getScriptType() != ScriptType.MWEB
                        && WalletLink.getLinkedCounterpartId(wallet) != null) {
                    linkNote = " (The linked private wallet isn't open, so only this wallet was pushed — open both and push again to transfer the pair.)";
                }

                writeFrame(out, message.toString().getBytes(StandardCharsets.UTF_8));
                JsonObject reply = JsonParser.parseString(new String(readFrame(in), StandardCharsets.UTF_8)).getAsJsonObject();
                boolean ok = reply.has("ok") && reply.get("ok").getAsBoolean();
                updateStatus(ok ? "Wallet sent ✓ — continue on your phone (it will ask for this wallet's password)." + linkNote
                                : "The phone reported a problem receiving the wallet.");
            } else {
                JsonObject message = JsonParser.parseString(new String(readFrame(in), StandardCharsets.UTF_8)).getAsJsonObject();
                if(!"wallet".equals(message.get("op").getAsString())) {
                    throw new IOException("Unexpected message from phone");
                }
                String name = message.has("name") ? message.get("name").getAsString() : "Mobile Wallet";
                byte[] fileBytes = Base64.getDecoder().decode(message.get("file").getAsString());
                File saved = saveReceived(name, fileBytes);
                String linkedNote = "";
                if(message.has("linked")) {
                    JsonObject linked = message.getAsJsonObject("linked");
                    String linkedName = linked.has("name") ? linked.get("name").getAsString() : name + " Private";
                    File linkedSaved = saveReceived(linkedName, Base64.getDecoder().decode(linked.get("file").getAsString()));
                    linkedNote = " Also received \"" + linkedName + "\" (" + linkedSaved.getName()
                            + ") — link them via Settings → Link Private Wallet after opening both.";
                }
                JsonObject reply = new JsonObject();
                reply.addProperty("ok", true);
                writeFrame(out, reply.toString().getBytes(StandardCharsets.UTF_8));
                updateStatus("Received \"" + name + "\" ✓ — File → Open Wallet → " + saved.getName()
                        + " (opens with the password it has on the phone)." + linkedNote);
            }
        } catch(Exception e) {
            if(!serverSocket.isClosed()) {
                log.error("Sparrow Link transfer failed", e);
                updateStatus("Link failed: " + e.getMessage());
            }
        } finally {
            closeSocket();
        }
    }

    private File saveReceived(String name, byte[] fileBytes) throws IOException {
        String safe = name.replaceAll("[^a-zA-Z0-9 _-]", "_").trim();
        if(safe.isEmpty()) {
            safe = "Mobile Wallet";
        }
        File file = Storage.getWalletFile(safe);
        int suffix = 2;
        while(file.exists()) {
            file = Storage.getWalletFile(safe + " " + suffix++);
        }
        Storage.createOwnerOnlyFile(file);
        Files.write(file.toPath(), fileBytes);
        return file;
    }

    private List<String> localAddresses() throws IOException {
        List<String> addresses = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while(interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if(!networkInterface.isUp() || networkInterface.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> interfaceAddresses = networkInterface.getInetAddresses();
            while(interfaceAddresses.hasMoreElements()) {
                InetAddress address = interfaceAddresses.nextElement();
                if(address instanceof Inet4Address) {
                    addresses.add(address.getHostAddress());
                }
            }
        }
        if(addresses.isEmpty()) {
            throw new IOException("No network addresses found — connect to WiFi or enable USB tethering");
        }
        return addresses;
    }

    private Image qrImage(String text) throws IOException {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 360, 360, Map.of(EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
            return new Image(new ByteArrayInputStream(baos.toByteArray()));
        } catch(Exception e) {
            throw new IOException("Could not create the pairing QR code", e);
        }
    }

    private void writeFrame(DataOutputStream out, byte[] plaintext) throws Exception {
        byte[] nonce = new byte[12];
        secureRandom.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(AAD);
        byte[] sealed = cipher.doFinal(plaintext);
        out.writeInt(nonce.length + sealed.length);
        out.write(nonce);
        out.write(sealed);
        out.flush();
    }

    private byte[] readFrame(DataInputStream in) throws Exception {
        int length = in.readInt();
        if(length < 13 || length > MAX_FRAME) {
            throw new IOException("Bad frame from phone");
        }
        byte[] frame = new byte[length];
        in.readFully(frame);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sessionKey, "AES"),
                new GCMParameterSpec(128, java.util.Arrays.copyOfRange(frame, 0, 12)));
        cipher.updateAAD(AAD);
        return cipher.doFinal(java.util.Arrays.copyOfRange(frame, 12, frame.length));
    }

    private void updateStatus(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
    }

    private void closeSocket() {
        try {
            serverSocket.close();
        } catch(IOException e) {
            //ignore
        }
    }
}
