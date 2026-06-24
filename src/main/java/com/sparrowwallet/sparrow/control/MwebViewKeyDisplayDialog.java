package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.sparrow.AppServices;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * Displays the MWEB view keys (scan secret and spend public key) for a keystore.
 * <p>
 * The format mirrors Electrum-LTC's "MWEB view keys" export (Wallet -&gt; Information):
 * two hex lines, the scan secret followed by the spend public key. Together these allow
 * a watch-only party to scan for and recognise MWEB outputs without the ability to spend.
 */
public class MwebViewKeyDisplayDialog extends Dialog<Void> {
    public MwebViewKeyDisplayDialog(Keystore keystore) {
        final DialogPane dialogPane = getDialogPane();
        dialogPane.getStylesheets().add(AppServices.class.getResource("general.css").toExternalForm());
        AppServices.setStageIcon(dialogPane.getScene().getWindow());
        setTitle("MWEB View Keys");

        String scanSecret = Utils.bytesToHex(keystore.getMwebScanPrivateKey().getPrivKeyBytes());
        String spendPubKey = Utils.bytesToHex(keystore.getMwebSpendPublicKey().getPubKey(true));
        //The exported view keys, in the same two-line format used by Electrum-LTC
        String viewKeys = scanSecret + "\n" + spendPubKey;

        VBox vBox = new VBox(15);
        vBox.setPadding(new Insets(20, 25, 10, 25));

        Label headerLabel = new Label("These are the MWEB view keys for this keystore. They allow a third party " +
                "(such as a payment processor) to detect MWEB transactions, but not to spend. Keep the scan secret private.");
        headerLabel.setWrapText(true);

        vBox.getChildren().addAll(headerLabel,
                createField("Scan secret:", scanSecret),
                createField("Spend public key:", spendPubKey));

        dialogPane.setContent(vBox);

        final ButtonType qrButtonType = new ButtonType("Show QR", ButtonBar.ButtonData.LEFT);
        final ButtonType copyButtonType = new ButtonType("Copy View Keys", ButtonBar.ButtonData.OTHER);
        final ButtonType closeButtonType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(qrButtonType, copyButtonType, closeButtonType);

        Button qrButton = (Button)dialogPane.lookupButton(qrButtonType);
        qrButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            Optional<ButtonType> optType = AppServices.showWarningDialog("Sensitive QR", "This QR contains the MWEB scan secret, which is a private key. " +
                    "Be careful before displaying or digitally recording it.\n\nAre you sure you want to continue?", ButtonType.YES, ButtonType.NO);
            if(optType.isPresent() && optType.get() == ButtonType.YES) {
                QRDisplayDialog qrDisplayDialog = new QRDisplayDialog(viewKeys);
                qrDisplayDialog.initOwner(getDialogPane().getScene().getWindow());
                qrDisplayDialog.showAndWait();
            }
        });

        Button copyButton = (Button)dialogPane.lookupButton(copyButtonType);
        copyButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            ClipboardContent content = new ClipboardContent();
            content.putString(viewKeys);
            Clipboard.getSystemClipboard().setContent(content);
        });

        dialogPane.setPrefWidth(550);
        dialogPane.setPrefHeight(260);
        AppServices.moveToActiveWindowScreen(this);
    }

    private VBox createField(String labelText, String value) {
        Label label = new Label(labelText);
        CopyableLabel valueLabel = new CopyableLabel(value);
        VBox fieldBox = new VBox(3, label, valueLabel);
        return fieldBox;
    }
}
