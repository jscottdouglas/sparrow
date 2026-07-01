package com.sparrowwallet.sparrow.wallet;

import com.google.common.eventbus.Subscribe;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.OutputDescriptor;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.uri.BitcoinURI;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.CurrencyRate;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.ExchangeSource;
import com.sparrowwallet.sparrow.control.*;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.io.Device;
import com.sparrowwallet.sparrow.io.Hwi;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.controlsfx.glyphfont.Glyph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ReceiveController extends WalletFormController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(ReceiveController.class);

    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    @FXML
    private CopyableTextField address;

    @FXML
    private TextField label;

    @FXML
    private CopyableLabel derivationPath;

    @FXML
    private Label lastUsed;

    @FXML
    private ImageView qrCode;

    @FXML
    private ScriptArea scriptPubKeyArea;

    @FXML
    private SelectableCodeArea outputDescriptor;

    @FXML
    private Button displayAddress;

    @FXML
    private TextField amount;

    @FXML
    private ComboBox<BitcoinUnit> amountUnit;

    @FXML
    private TextField fiatAmount;

    @FXML
    private Label fiatCurrencyCode;

    //Guards against the LTC<->fiat amount listeners re-triggering each other while one updates the other
    private boolean syncingAmounts;

    private NodeEntry currentEntry;

    private QRDisplayDialog addressQrDialog;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        EventManager.get().register(this);
    }

    @Override
    public void initializeView() {
        address.setSkin(new AddressTextFieldSkin(address));
        initializeScriptField(scriptPubKeyArea);

        displayAddress.managedProperty().bind(displayAddress.visibleProperty());
        displayAddress.setVisible(false);

        qrCode.setOnMouseClicked(event -> {
            if(currentEntry != null && addressQrDialog == null) {
                addressQrDialog = new QRDisplayDialog(getPaymentUri());
                addressQrDialog.initOwner(address.getScene().getWindow());
                addressQrDialog.showAndWait();
                addressQrDialog = null;
            }
        });

        initializeAmountFields();

        refreshAddress();
    }

    private void initializeAmountFields() {
        amount.setTextFormatter(new CoinTextFormatter(Config.get().getUnitFormat()));
        amount.textProperty().addListener((observable, oldValue, newValue) -> {
            if(!syncingAmounts) {
                syncingAmounts = true;
                try {
                    setFiatFromAmount();
                } finally {
                    syncingAmounts = false;
                }
            }
            updateQR();
        });

        fiatAmount.setTextFormatter(new TextFormatter<>(change -> {
            //Mirror the LTC field: auto-insert a leading zero when the fiat amount starts with the decimal separator
            String sep = (Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat()).getDecimalSeparator();
            if(!change.isDeleted() && change.getControlNewText().startsWith(sep)) {
                change.setText("0" + change.getText());
                //Caret after the inserted text - getCaretPosition() can be stale on the field's first edit, dropping the
                //caret between the "0" and the separator so the next digit lands on the wrong side (".10" became "0.01").
                int caret = change.getRangeStart() + change.getText().length();
                change.setCaretPosition(caret);
                change.setAnchor(caret);
            }
            return change;
        }));

        //Typing a fiat amount fills in the LTC field, which (via the amount listener) refreshes the QR
        fiatAmount.textProperty().addListener((observable, oldValue, newValue) -> {
            if(syncingAmounts) {
                return;
            }
            syncingAmounts = true;
            try {
                setAmountFromFiat();
            } finally {
                syncingAmounts = false;
            }
        });

        amountUnit.getSelectionModel().select(BitcoinUnit.BTC.equals(getBitcoinUnit()) ? 0 : 1);
        amountUnit.valueProperty().addListener((observable, oldValue, newValue) -> {
            Long value = getRecipientValueSats(oldValue);
            if(value != null) {
                UnitFormat unitFormat = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
                DecimalFormat df = new DecimalFormat("#.#", unitFormat.getDecimalFormatSymbols());
                df.setMaximumFractionDigits(8);
                amount.setText(df.format(newValue.getValue(value)));
            }
        });

        CurrencyRate currencyRate = AppServices.getFiatCurrencyExchangeRate();
        boolean fiatAvailable = currencyRate != null && currencyRate.isAvailable() && Config.get().getExchangeSource() != ExchangeSource.NONE;
        fiatAmount.setDisable(!fiatAvailable);
        if(fiatAvailable && currencyRate.getCurrency() != null) {
            fiatCurrencyCode.setText(currencyRate.getCurrency().getSymbol());
        }
    }

    public void setNodeEntry(NodeEntry nodeEntry) {
        if(currentEntry != null) {
            label.textProperty().unbindBidirectional(currentEntry.labelProperty());
        }

        this.currentEntry = nodeEntry;
        address.setText(nodeEntry.getAddress().toString());
        label.textProperty().bindBidirectional(nodeEntry.labelProperty());
        updateDerivationPath(nodeEntry);

        updateLastUsed();

        Image qrImage = getQrCode(getPaymentUri());
        if(qrImage != null) {
            qrCode.setImage(qrImage);
        }

        scriptPubKeyArea.clear();
        scriptPubKeyArea.appendScript(nodeEntry.getOutputScript(), null, null);

        outputDescriptor.clear();
        outputDescriptor.append(nodeEntry.getOutputDescriptor(), "descriptor-text");

        updateDisplayAddress(AppServices.getDevices());
    }

    private void updateDerivationPath(NodeEntry nodeEntry) {
        derivationPath.setText(getDerivationPath(nodeEntry.getNode()));
    }

    private void updateLastUsed() {
        Set<BlockTransactionHashIndex> currentOutputs = currentEntry.getNode().getTransactionOutputs();
        if(AppServices.isConnected() && currentOutputs.isEmpty()) {
            lastUsed.setText("Never");
            lastUsed.setGraphic(getUnusedGlyph());
            address.getStyleClass().remove("error");
        } else if(!currentOutputs.isEmpty()) {
            long count = currentOutputs.size();
            BlockTransactionHashIndex lastUsedReference = currentOutputs.stream().skip(count - 1).findFirst().get();
            lastUsed.setText(lastUsedReference.getHeight() <= 0 ? "Unconfirmed Transaction" : (lastUsedReference.getDate() == null ? "Unknown" : DATE_FORMAT.format(lastUsedReference.getDate())));
            lastUsed.setGraphic(getWarningGlyph());
            if(!address.getStyleClass().contains("error")) {
                address.getStyleClass().add("error");
            }
        } else {
            lastUsed.setText("Unknown");
            lastUsed.setGraphic(getUnknownGlyph());
            address.getStyleClass().remove("error");
        }
    }

    private void updateDisplayAddress(List<Device> devices) {
        Wallet wallet = getWalletForm().getWallet();
        OutputDescriptor walletDescriptor = OutputDescriptor.getOutputDescriptor(walletForm.getWallet());
        List<String> walletFingerprints = walletDescriptor.getExtendedPublicKeys().stream().map(extKey -> walletDescriptor.getKeyDerivation(extKey).getMasterFingerprint()).collect(Collectors.toList());

        List<Device> addressDevices = devices.stream().filter(device -> walletFingerprints.contains(device.getFingerprint())).collect(Collectors.toList());
        if(addressDevices.isEmpty()) {
            addressDevices = devices.stream().filter(device -> device.isNeedsPinSent() || device.isNeedsPassphraseSent()).collect(Collectors.toList());
        }

        if(!addressDevices.isEmpty()) {
            if(currentEntry != null) {
                displayAddress.setVisible(true);
            }

            displayAddress.setUserData(addressDevices);
            return;
        } else if(currentEntry != null && wallet.getKeystores().stream().anyMatch(keystore -> keystore.getSource().equals(KeystoreSource.HW_USB) || keystore.getSource().equals(KeystoreSource.SW_WATCH))) {
            displayAddress.setVisible(true);
            displayAddress.setUserData(null);
            return;
        }

        displayAddress.setVisible(false);
        displayAddress.setUserData(null);
    }

    private Image getQrCode(String address) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix qrMatrix = qrCodeWriter.encode(address, BarcodeFormat.QR_CODE, 130, 130, Map.of(EncodeHintType.MARGIN, 2));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(qrMatrix, "PNG", baos, new MatrixToImageConfig());

            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            return new Image(bais);
        } catch(Exception e) {
            log.error("Error generating QR", e);
        }

        return null;
    }

    private BitcoinUnit getBitcoinUnit() {
        BitcoinUnit unit = Config.get().getBitcoinUnit();
        if(unit == null || unit == BitcoinUnit.AUTO) {
            return BitcoinUnit.BTC;
        }
        return unit;
    }

    private Long getRecipientValueSats() {
        return getRecipientValueSats(amountUnit.getValue());
    }

    private Long getRecipientValueSats(BitcoinUnit bitcoinUnit) {
        return getRecipientValueSats(Config.get().getUnitFormat(), bitcoinUnit);
    }

    private Long getRecipientValueSats(UnitFormat unitFormat, BitcoinUnit bitcoinUnit) {
        if(bitcoinUnit != null && amount.getText() != null && !amount.getText().isEmpty()) {
            UnitFormat format = unitFormat == null ? UnitFormat.DOT : unitFormat;
            try {
                double fieldValue = Double.parseDouble(amount.getText().replaceAll(Pattern.quote(format.getGroupingSeparator()), "").replaceAll(",", "."));
                return bitcoinUnit.getSatsValue(fieldValue);
            } catch(NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    private void setFiatFromAmount() {
        CurrencyRate currencyRate = AppServices.getFiatCurrencyExchangeRate();
        Long sats = getRecipientValueSats();
        if(sats != null && sats >= 0 && currencyRate != null && currencyRate.isAvailable() && Config.get().getExchangeSource() != ExchangeSource.NONE) {
            UnitFormat format = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
            double fiatValue = BigDecimal.valueOf(sats).divide(BigDecimal.valueOf(Transaction.SATOSHIS_PER_BITCOIN))
                    .multiply(BigDecimal.valueOf(currencyRate.getBtcRate())).doubleValue();
            fiatAmount.setText(format.formatCurrencyValue(fiatValue));
            if(currencyRate.getCurrency() != null) {
                fiatCurrencyCode.setText(currencyRate.getCurrency().getSymbol());
            }
        } else {
            fiatAmount.setText("");
        }
    }

    private void setAmountFromFiat() {
        Long sats = getFiatValueSats();
        if(sats == null) {
            amount.setText("");
        } else {
            UnitFormat unitFormat = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
            DecimalFormat df = new DecimalFormat("#.#", unitFormat.getDecimalFormatSymbols());
            df.setMaximumFractionDigits(8);
            amount.setText(df.format(amountUnit.getValue().getValue(sats)));
        }
    }

    private Long getFiatValueSats() {
        CurrencyRate currencyRate = AppServices.getFiatCurrencyExchangeRate();
        if(fiatAmount.getText() != null && !fiatAmount.getText().isEmpty() && currencyRate != null && currencyRate.isAvailable() && currencyRate.getBtcRate() > 0.0) {
            UnitFormat format = Config.get().getUnitFormat() == null ? UnitFormat.DOT : Config.get().getUnitFormat();
            try {
                double fiatValue = Double.parseDouble(fiatAmount.getText().replaceAll(Pattern.quote(format.getGroupingSeparator()), "").replaceAll(",", "."));
                if(fiatValue <= 0.0) {
                    return null;
                }
                return BigDecimal.valueOf(fiatValue).multiply(BigDecimal.valueOf(Transaction.SATOSHIS_PER_BITCOIN))
                        .divide(BigDecimal.valueOf(currencyRate.getBtcRate()), 0, RoundingMode.HALF_UP).longValue();
            } catch(NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    //The QR (and enlarged QR dialog) encode a litecoin: BIP21 URI carrying the requested amount when one is entered,
    //so a scan yields address + amount. With no amount, it falls back to the bare address as before.
    private String getPaymentUri() {
        if(currentEntry == null) {
            return null;
        }

        String addressString = currentEntry.getAddress().toString();
        Long sats = getRecipientValueSats();
        if(sats == null || sats <= 0) {
            return addressString;
        }

        BigDecimal ltcValue = BigDecimal.valueOf(sats).divide(BigDecimal.valueOf(Transaction.SATOSHIS_PER_BITCOIN));
        DecimalFormat df = new DecimalFormat("0.########", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return BitcoinURI.BITCOIN_SCHEME + ":" + addressString + "?" + BitcoinURI.FIELD_AMOUNT + "=" + df.format(ltcValue);
    }

    private void updateQR() {
        if(currentEntry == null) {
            return;
        }

        Image qrImage = getQrCode(getPaymentUri());
        if(qrImage != null) {
            qrCode.setImage(qrImage);
        }
    }

    public void getNewAddress(ActionEvent event) {
        refreshAddress();
        if(currentEntry != null) {
            ensureSufficientGapLimit(currentEntry.getNode().getIndex());
        }
    }

    public void refreshAddress() {
        NodeEntry freshEntry = getWalletForm().getFreshNodeEntry(KeyPurpose.RECEIVE, currentEntry);
        while(freshEntry.getLabel() != null && !freshEntry.getLabel().isEmpty()) {
            freshEntry = getWalletForm().getFreshNodeEntry(KeyPurpose.RECEIVE, freshEntry);
        }
        setNodeEntry(freshEntry);
        if(addressQrDialog != null) {
            addressQrDialog.close();
        }
    }

    private void ensureSufficientGapLimit(int index) {
        Wallet wallet = getWalletForm().getWallet();
        Integer highestIndex = wallet.getNode(KeyPurpose.RECEIVE).getHighestUsedIndex();
        int highestUsedIndex = highestIndex == null ? -1 : highestIndex;
        int existingGapLimit = wallet.getGapLimit();
        if(index > highestUsedIndex + existingGapLimit) {
            wallet.setGapLimit(Math.max(wallet.getGapLimit(), index - highestUsedIndex));
            EventManager.get().post(new WalletGapLimitChangedEvent(getWalletForm().getWalletId(), wallet, existingGapLimit));
        }
    }

    @SuppressWarnings("unchecked")
    public void displayAddress(ActionEvent event) {
        Wallet wallet = getWalletForm().getWallet();
        if(currentEntry != null) {
            OutputDescriptor addressDescriptor = OutputDescriptor.getOutputDescriptor(walletForm.getWallet(), currentEntry.getNode().getKeyPurpose(), currentEntry.getNode().getIndex());

            List<Device> possibleDevices = (List<Device>)displayAddress.getUserData();
            if(possibleDevices != null && !possibleDevices.isEmpty()) {
                if(possibleDevices.size() > 1 || possibleDevices.get(0).isNeedsPinSent() || possibleDevices.get(0).isNeedsPassphraseSent()) {
                    DeviceDisplayAddressDialog dlg = new DeviceDisplayAddressDialog(wallet, addressDescriptor);
                    dlg.initOwner(displayAddress.getScene().getWindow());
                    dlg.showAndWait();
                } else {
                    Device actualDevice = possibleDevices.get(0);
                    Hwi.DisplayAddressService displayAddressService = new Hwi.DisplayAddressService(actualDevice, "", wallet.getScriptType(), addressDescriptor,
                            OutputDescriptor.getOutputDescriptor(walletForm.getWallet()), walletForm.getWallet().getFullName(), getDeviceRegistration(actualDevice));
                    displayAddressService.setOnSucceeded(successEvent -> {
                        updateDeviceRegistrations(actualDevice, displayAddressService.getNewDeviceRegistrations());
                    });
                    displayAddressService.setOnFailed(failedEvent -> {
                        Platform.runLater(() -> {
                            DeviceDisplayAddressDialog dlg = new DeviceDisplayAddressDialog(wallet, addressDescriptor);
                            dlg.initOwner(displayAddress.getScene().getWindow());
                            dlg.showAndWait();
                        });
                    });
                    displayAddressService.start();
                }
            } else {
                DeviceDisplayAddressDialog dlg = new DeviceDisplayAddressDialog(wallet, addressDescriptor);
                dlg.initOwner(displayAddress.getScene().getWindow());
                dlg.showAndWait();
            }
        }
    }

    private byte[] getDeviceRegistration(Device device) {
        Optional<Keystore> optKeystore = getWalletForm().getWallet().getKeystores().stream()
                .filter(keystore -> keystore.getKeyDerivation().getMasterFingerprint().equals(device.getFingerprint()) && keystore.getDeviceRegistration() != null).findFirst();
        return optKeystore.map(Keystore::getDeviceRegistration).orElse(null);
    }

    private void updateDeviceRegistrations(Device device, Set<byte[]> newDeviceRegistrations) {
        if(!newDeviceRegistrations.isEmpty()) {
            List<Keystore> registrationKeystores = getDeviceRegistrationKeystores(device);
            if(!registrationKeystores.isEmpty()) {
                registrationKeystores.forEach(keystore -> keystore.setDeviceRegistration(newDeviceRegistrations.iterator().next()));
                EventManager.get().post(new KeystoreDeviceRegistrationsChangedEvent(getWalletForm().getWallet(), registrationKeystores));
            }
        }
    }

    private List<Keystore> getDeviceRegistrationKeystores(Device device) {
        return getWalletForm().getWallet().getKeystores().stream().filter(keystore -> keystore.getKeyDerivation().getMasterFingerprint().equals(device.getFingerprint())).toList();
    }

    public void clear() {
        if(currentEntry != null) {
            label.textProperty().unbindBidirectional(currentEntry.labelProperty());
        }

        address.setText("");
        label.setText("");
        amount.setText("");
        fiatAmount.setText("");
        derivationPath.setText("");
        lastUsed.setText("");
        lastUsed.setGraphic(null);
        qrCode.setImage(null);
        scriptPubKeyArea.clear();
        outputDescriptor.clear();
        this.currentEntry = null;
    }

    public static Glyph getUnusedGlyph() {
        Glyph checkGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.CHECK_CIRCLE);
        checkGlyph.getStyleClass().add("unused-check");
        checkGlyph.setFontSize(12);
        return checkGlyph;
    }

    public static Glyph getWarningGlyph() {
        Glyph duplicateGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.EXCLAMATION_CIRCLE);
        duplicateGlyph.getStyleClass().add("duplicate-warning");
        duplicateGlyph.setFontSize(12);
        return duplicateGlyph;
    }

    public static Glyph getUnknownGlyph() {
        Glyph duplicateGlyph = new Glyph(FontAwesome5.FONT_NAME, FontAwesome5.Glyph.QUESTION_CIRCLE);
        duplicateGlyph.setFontSize(12);
        return duplicateGlyph;
    }

    @Subscribe
    public void unitFormatChanged(UnitFormatChangedEvent event) {
        if(amount.getTextFormatter() instanceof CoinTextFormatter coinTextFormatter && coinTextFormatter.getUnitFormat() != event.getUnitFormat()) {
            Long value = getRecipientValueSats(coinTextFormatter.getUnitFormat(), amountUnit.getValue());
            amount.setTextFormatter(new CoinTextFormatter(event.getUnitFormat()));
            if(value != null) {
                syncingAmounts = true;
                try {
                    DecimalFormat df = new DecimalFormat("#.#", event.getUnitFormat().getDecimalFormatSymbols());
                    df.setMaximumFractionDigits(8);
                    amount.setText(df.format(amountUnit.getValue().getValue(value)));
                } finally {
                    syncingAmounts = false;
                }
            }
        }
    }

    @Subscribe
    public void exchangeRatesUpdated(ExchangeRatesUpdatedEvent event) {
        CurrencyRate currencyRate = event.getCurrencyRate();
        boolean fiatAvailable = currencyRate != null && currencyRate.isAvailable() && Config.get().getExchangeSource() != ExchangeSource.NONE;
        fiatAmount.setDisable(!fiatAvailable);
        if(fiatAvailable && currencyRate.getCurrency() != null) {
            fiatCurrencyCode.setText(currencyRate.getCurrency().getSymbol());
        }
        syncingAmounts = true;
        try {
            setFiatFromAmount();
        } finally {
            syncingAmounts = false;
        }
    }

    @Subscribe
    public void fiatCurrencySelected(FiatCurrencySelectedEvent event) {
        if(event.getExchangeSource() == ExchangeSource.NONE) {
            syncingAmounts = true;
            try {
                fiatAmount.setText("");
                fiatCurrencyCode.setText("");
            } finally {
                syncingAmounts = false;
            }
            fiatAmount.setDisable(true);
        } else {
            fiatAmount.setDisable(false);
        }
    }

    @Subscribe
    public void walletAddressesChanged(WalletAddressesChangedEvent event) {
        displayAddress.setUserData(null);
    }

    @Subscribe
    public void receiveTo(ReceiveToEvent event) {
        if(event.getReceiveEntry().getWallet().equals(getWalletForm().getWallet())) {
            setNodeEntry(event.getReceiveEntry());
        }
    }

    @Subscribe
    public void walletNodesChanged(WalletNodesChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            if(currentEntry != null) {
                label.textProperty().unbindBidirectional(currentEntry.labelProperty());
                currentEntry = null;
            }
            refreshAddress();
        }
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            if(currentEntry != null && event.getHistoryChangedNodes().contains(currentEntry.getNode())) {
                refreshAddress();
            }
        }
    }

    @Subscribe
    public void usbDevicesFound(UsbDeviceEvent event) {
        updateDisplayAddress(event.getDevices());
    }

    @Subscribe
    public void connection(ConnectionEvent event) {
        updateLastUsed();
    }

    @Subscribe
    public void disconnection(DisconnectionEvent event) {
        updateLastUsed();
    }
}