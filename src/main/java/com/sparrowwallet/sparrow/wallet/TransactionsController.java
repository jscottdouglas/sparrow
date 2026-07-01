package com.sparrowwallet.sparrow.wallet;

import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.*;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.WalletTransactions;
import com.sparrowwallet.sparrow.net.ExchangeSource;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.address.Address;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.drongo.wallet.Payment;
import com.sparrowwallet.drongo.wallet.Wallet;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.controlsfx.control.MasterDetailPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tornadofx.control.Field;

import java.io.File;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.ResourceBundle;

public class TransactionsController extends WalletFormController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(TransactionsController.class);

    private static final DateFormat LOG_DATE_FORMAT = new SimpleDateFormat("[MMM dd HH:mm:ss]");
    private static final int LOADING_LOG_MAX_CHARS = 10000;

    @FXML
    private CopyableCoinLabel balance;

    @FXML
    private FiatLabel fiatBalance;

    @FXML
    private CopyableCoinLabel mempoolBalance;

    @FXML
    private FiatLabel fiatMempoolBalance;

    @FXML
    private CopyableLabel transactionCount;

    @FXML
    private MasterDetailPane transactionsMasterDetail;

    @FXML
    private TransactionsTreeTable transactionsTable;

    @FXML
    private TextArea loadingLog;

    @FXML
    private BalanceChart balanceChart;

    @FXML
    private Button exportCsv;

    @FXML
    private Field moveFundsField;

    @FXML
    private Button moveToPrivate;

    @FXML
    private Button moveToPublic;

    @FXML
    private Field balanceField;

    @FXML
    private Field publicBalanceField;

    @FXML
    private CopyableCoinLabel publicBalance;

    @FXML
    private FiatLabel fiatPublicBalance;

    @FXML
    private Field privateBalanceField;

    @FXML
    private CopyableCoinLabel privateBalance;

    @FXML
    private FiatLabel fiatPrivateBalance;

    @FXML
    private Field combinedBalanceField;

    @FXML
    private CopyableCoinLabel combinedBalance;

    @FXML
    private FiatLabel fiatCombinedBalance;

    @FXML
    private Field mempoolBalanceField;

    @FXML
    private Field publicMempoolField;

    @FXML
    private CopyableCoinLabel publicMempoolBalance;

    @FXML
    private FiatLabel fiatPublicMempoolBalance;

    @FXML
    private Field privateMempoolField;

    @FXML
    private CopyableCoinLabel privateMempoolBalance;

    @FXML
    private FiatLabel fiatPrivateMempoolBalance;

    @FXML
    private Field linkNoticeField;

    @FXML
    private Label linkNotice;

    private long publicBalanceValue;

    private long privateBalanceValue;

    private long combinedBalanceValue;

    private long publicMempoolValue;

    private long privateMempoolValue;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        EventManager.get().register(this);
    }

    @Override
    public void initializeView() {
        WalletTransactionsEntry walletTransactionsEntry = getWalletForm().getWalletTransactionsEntry();

        transactionsTable.initialize(walletTransactionsEntry);

        balance.valueProperty().addListener((observable, oldValue, newValue) -> {
            setFiatBalance(fiatBalance, AppServices.getFiatCurrencyExchangeRate(), newValue.longValue());
        });
        balance.setValue(walletTransactionsEntry.getBalance());
        mempoolBalance.valueProperty().addListener((observable, oldValue, newValue) -> {
            setFiatBalance(fiatMempoolBalance, AppServices.getFiatCurrencyExchangeRate(), newValue.longValue());
        });
        mempoolBalance.setValue(walletTransactionsEntry.getMempoolBalance());
        setTransactionCount(walletTransactionsEntry);
        balanceChart.initialize(walletTransactionsEntry);

        transactionsTable.getSelectionModel().getSelectedIndices().addListener((ListChangeListener<Integer>) c -> {
            TreeItem<Entry> selectedItem = transactionsTable.getSelectionModel().getSelectedItem();
            if(selectedItem != null && selectedItem.getValue() instanceof TransactionEntry) {
                balanceChart.select((TransactionEntry)selectedItem.getValue());
            }
        });

        transactionsMasterDetail.setShowDetailNode(Config.get().isShowLoadingLog());
        loadingLog.appendText("Wallet loading history for " + getWalletForm().getWallet().getFullDisplayName());
        loadingLog.setEditable(false);

        //Fiat listeners for the linked breakdown - labels exist even when hidden, so attach once here
        publicBalance.valueProperty().addListener((observable, oldValue, newValue) ->
                setFiatBalance(fiatPublicBalance, AppServices.getFiatCurrencyExchangeRate(), newValue.longValue()));
        privateBalance.valueProperty().addListener((observable, oldValue, newValue) ->
                setFiatBalance(fiatPrivateBalance, AppServices.getFiatCurrencyExchangeRate(), newValue.longValue()));
        combinedBalance.valueProperty().addListener((observable, oldValue, newValue) ->
                setFiatBalance(fiatCombinedBalance, AppServices.getFiatCurrencyExchangeRate(), newValue.longValue()));
        publicMempoolBalance.valueProperty().addListener((observable, oldValue, newValue) ->
                setFiatBalance(fiatPublicMempoolBalance, AppServices.getFiatCurrencyExchangeRate(), newValue.longValue()));
        privateMempoolBalance.valueProperty().addListener((observable, oldValue, newValue) ->
                setFiatBalance(fiatPrivateMempoolBalance, AppServices.getFiatCurrencyExchangeRate(), newValue.longValue()));

        updateLinkedUi();
    }

    /**
     * Reflects this wallet's public/private association in the header. When linked and both wallets are open,
     * the single Balance/Mempool lines are replaced by a Public / Private / Total breakdown and both Move
     * buttons are shown; when linked but the counterpart wallet is not open, a notice is shown instead.
     */
    private void updateLinkedUi() {
        Wallet wallet = getWalletForm().getWallet();
        boolean linked = WalletLink.isLinked(wallet);
        Wallet counterpart = linked ? WalletLink.getLinkedCounterpart(wallet) : null;
        boolean unified = linked && counterpart != null;

        //Generic single-wallet lines show unless the unified breakdown is taking over
        show(balanceField, !unified);
        show(mempoolBalanceField, !unified);

        show(publicBalanceField, unified);
        show(privateBalanceField, unified);
        show(combinedBalanceField, unified);
        show(moveFundsField, unified);

        boolean notice = linked && counterpart == null;
        show(linkNoticeField, notice);
        if(notice) {
            boolean thisIsMweb = wallet.getScriptType() == ScriptType.MWEB;
            linkNotice.setText("Linked " + (thisIsMweb ? "public" : "private") + " wallet is not open. Open it to see the combined balance and move funds between public and private.");
        }

        if(unified) {
            updateBalanceBreakdown();
        } else {
            show(publicMempoolField, false);
            show(privateMempoolField, false);
        }
    }

    private void updateBalanceBreakdown() {
        Wallet wallet = getWalletForm().getWallet();
        Wallet publicWallet = WalletLink.getPublicWallet(wallet);
        Wallet mwebWallet = WalletLink.getMwebWallet(wallet);
        if(publicWallet == null || mwebWallet == null) {
            return;
        }

        //Fresh WalletTransactionsEntry objects are pure, side-effect-free balance calcs over each wallet
        WalletTransactionsEntry publicEntry = new WalletTransactionsEntry(publicWallet);
        WalletTransactionsEntry privateEntry = new WalletTransactionsEntry(mwebWallet);

        publicBalanceValue = publicEntry.getBalance();
        privateBalanceValue = privateEntry.getBalance();
        combinedBalanceValue = publicBalanceValue + privateBalanceValue;
        //"Pending" on the public side includes both unconfirmed activity and incoming peg-outs that have confirmed
        //but are still maturing. A peg-out has no public output at all while in the MWEB mempool (the canonical output
        //is created by the miner-built HogEx tx), so it only becomes visible from its first confirmation; until it
        //reaches PEGOUT_MATURITY_THRESHOLD confirmations it is confirmed-but-unspendable, which we surface here.
        publicMempoolValue = publicEntry.getMempoolBalance() + getImmaturePegoutValue(publicWallet);
        privateMempoolValue = privateEntry.getMempoolBalance();

        publicBalance.setValue(publicBalanceValue);
        privateBalance.setValue(privateBalanceValue);
        combinedBalance.setValue(combinedBalanceValue);
        publicMempoolBalance.setValue(publicMempoolValue);
        privateMempoolBalance.setValue(privateMempoolValue);

        //Pending lines appear only when there is an unconfirmed or still-maturing balance on that side
        show(publicMempoolField, publicMempoolValue != 0);
        show(privateMempoolField, privateMempoolValue != 0);
    }

    /**
     * Total value of incoming peg-out outputs that have confirmed but are not yet spendable (fewer than
     * PEGOUT_MATURITY_THRESHOLD confirmations). These are real, confirmed outputs on the public chain - they are
     * already included in the public balance - but are shown separately as "pending" so a Move to Public is visible
     * from its first confirmation through to maturity, mirroring how the private side shows the outflow immediately.
     */
    private long getImmaturePegoutValue(Wallet wallet) {
        Integer blockHeight = AppServices.getCurrentBlockHeight() != null ? AppServices.getCurrentBlockHeight() : wallet.getStoredBlockHeight();
        if(blockHeight == null) {
            return 0L;
        }

        long total = 0L;
        for(BlockTransactionHashIndex utxo : wallet.getWalletUtxos().keySet()) {
            BlockTransaction blockTransaction = wallet.getWalletTransaction(utxo.getHash());
            if(blockTransaction != null && blockTransaction.getTransaction() != null && blockTransaction.getTransaction().isHogEx()) {
                int confirmations = utxo.getConfirmations(blockHeight);
                if(confirmations > 0 && confirmations < Transaction.PEGOUT_MATURITY_THRESHOLD) {
                    total += utxo.getValue();
                }
            }
        }

        return total;
    }

    /**
     * Re-evaluates the linked UI when a history/nodes change arrives for this wallet or its linked counterpart
     * (which also catches the counterpart wallet being opened after this view loads).
     */
    private void refreshLinkedUi(Wallet eventWallet) {
        Wallet wallet = getWalletForm().getWallet();
        String counterpartId = WalletLink.getLinkedCounterpartId(wallet);
        if(counterpartId == null) {
            return;
        }
        if(eventWallet.equals(wallet) || counterpartId.equals(WalletLink.getWalletId(eventWallet))) {
            updateLinkedUi();
        }
    }

    public void moveToPrivate(ActionEvent event) {
        Wallet wallet = getWalletForm().getWallet();
        moveFunds(WalletLink.getPublicWallet(wallet), WalletLink.getMwebWallet(wallet));
    }

    public void moveToPublic(ActionEvent event) {
        Wallet wallet = getWalletForm().getWallet();
        moveFunds(WalletLink.getMwebWallet(wallet), WalletLink.getPublicWallet(wallet));
    }

    /**
     * Pre-fills a peg send: spends from sendingWallet and pays a fresh receive address from targetWallet,
     * switching to the sending wallet's Send tab. Each wallet signs only its own side.
     */
    private void moveFunds(Wallet sendingWallet, Wallet targetWallet) {
        if(sendingWallet == null || targetWallet == null) {
            return;
        }

        try {
            Address address = targetWallet.getFreshNode(KeyPurpose.RECEIVE).getAddress();
            boolean toPrivate = targetWallet.getScriptType() == ScriptType.MWEB;
            String label = toPrivate ? "Move to Private" : "Move to Public";
            Payment payment = new Payment(address, label, 0, false);

            List<BlockTransactionHashIndex> utxos = new ArrayList<>(sendingWallet.getSpendableUtxos().keySet());
            EventManager.get().post(new SendActionEvent(sendingWallet, utxos, true));
            Platform.runLater(() -> EventManager.get().post(new SendPaymentsEvent(sendingWallet, List.of(payment))));
        } catch(Exception e) {
            log.error("Error preparing move between public and private balances", e);
            AppServices.showErrorDialog("Error preparing move", e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private static void show(Field field, boolean shown) {
        field.setVisible(shown);
        field.setManaged(shown);
    }

    private void setTransactionCount(WalletTransactionsEntry walletTransactionsEntry) {
        transactionCount.setText(walletTransactionsEntry.getChildren() != null ? Integer.toString(walletTransactionsEntry.getChildren().size()) : "0");
    }

    public void exportCSV(ActionEvent event) {
        Wallet wallet = getWalletForm().getWallet();

        Stage window = new Stage();
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Transactions as CSV");
        fileChooser.setInitialFileName(wallet.getFullName() + "-transactions.csv");
        AppServices.moveToActiveWindowScreen(window, 800, 450);
        File file = fileChooser.showSaveDialog(window);
        if(file != null) {
            FileWalletExportPane.FileWalletExportService exportService = new FileWalletExportPane.FileWalletExportService(new WalletTransactions(getWalletForm()), file, wallet, null);
            exportService.setOnFailed(failedEvent -> {
                Throwable e = failedEvent.getSource().getException();
                log.error("Error exporting transactions as CSV", e);
                AppServices.showErrorDialog("Error exporting transactions as CSV", e.getMessage());
            });
            exportService.start();
        }
    }

    private void logMessage(String logMessage) {
        if(logMessage != null) {
            logMessage = logMessage.replace("m/", "../");
            String date = LOG_DATE_FORMAT.format(new Date());
            String logLine = "\n" + date + " " + logMessage;
            Platform.runLater(() -> {
                int lastLineStart = loadingLog.getText().lastIndexOf("\n");
                if(lastLineStart < 0 || !loadingLog.getText().substring(lastLineStart).equals(logLine)) {
                    if(loadingLog.getLength() > LOADING_LOG_MAX_CHARS) {
                        int start = loadingLog.getText().indexOf('\n', loadingLog.getLength() - LOADING_LOG_MAX_CHARS);
                        loadingLog.replaceText(0, loadingLog.getLength(), "[truncated]" + loadingLog.getText().substring(start > -1 ? start : 0, loadingLog.getLength()) + logLine);
                    } else {
                        loadingLog.appendText(logLine);
                    }

                    loadingLog.positionCaret(loadingLog.getLength() - logLine.length() + 1);
                }
            });
        }
    }

    @Subscribe
    public void walletNodesChanged(WalletNodesChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            WalletTransactionsEntry walletTransactionsEntry = getWalletForm().getWalletTransactionsEntry();

            transactionsTable.updateAll(walletTransactionsEntry);
            balance.setValue(walletTransactionsEntry.getBalance());
            mempoolBalance.setValue(walletTransactionsEntry.getMempoolBalance());
            balanceChart.update(walletTransactionsEntry);
            setTransactionCount(walletTransactionsEntry);
        }

        refreshLinkedUi(event.getWallet());
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            WalletTransactionsEntry walletTransactionsEntry = getWalletForm().getWalletTransactionsEntry();

            //Will automatically update transactionsTable transactions and recalculate balances
            walletTransactionsEntry.updateTransactions();

            transactionsTable.updateHistory();
            balance.setValue(walletTransactionsEntry.getBalance());
            mempoolBalance.setValue(walletTransactionsEntry.getMempoolBalance());
            balanceChart.update(walletTransactionsEntry);
            setTransactionCount(walletTransactionsEntry);
        }

        refreshLinkedUi(event.getWallet());
    }

    @Subscribe
    public void walletEntryLabelChanged(WalletEntryLabelsChangedEvent event) {
        if(event.fromThisOrNested(walletForm.getWallet())) {
            for(Entry entry : event.getEntries()) {
                transactionsTable.updateLabel(entry);
            }
            balanceChart.update(getWalletForm().getWalletTransactionsEntry());
        }
    }

    @Subscribe
    public void unitFormatChanged(UnitFormatChangedEvent event) {
        transactionsTable.setUnitFormat(getWalletForm().getWallet(), event.getUnitFormat(), event.getBitcoinUnit());
        balanceChart.setUnitFormat(getWalletForm().getWallet(), event.getUnitFormat(), event.getBitcoinUnit());
        balance.refresh(event.getUnitFormat(), event.getBitcoinUnit());
        mempoolBalance.refresh(event.getUnitFormat(), event.getBitcoinUnit());
        publicBalance.refresh(event.getUnitFormat(), event.getBitcoinUnit());
        privateBalance.refresh(event.getUnitFormat(), event.getBitcoinUnit());
        combinedBalance.refresh(event.getUnitFormat(), event.getBitcoinUnit());
        publicMempoolBalance.refresh(event.getUnitFormat(), event.getBitcoinUnit());
        privateMempoolBalance.refresh(event.getUnitFormat(), event.getBitcoinUnit());
        fiatBalance.refresh(event.getUnitFormat());
        fiatMempoolBalance.refresh(event.getUnitFormat());
        fiatPublicBalance.refresh(event.getUnitFormat());
        fiatPrivateBalance.refresh(event.getUnitFormat());
        fiatCombinedBalance.refresh(event.getUnitFormat());
        fiatPublicMempoolBalance.refresh(event.getUnitFormat());
        fiatPrivateMempoolBalance.refresh(event.getUnitFormat());
    }

    @Subscribe
    public void hideAmountsStatusChanged(HideAmountsStatusEvent event) {
        transactionsTable.refresh();
        balanceChart.refreshAxisLabels();
        balance.refresh();
        mempoolBalance.refresh();
        publicBalance.refresh();
        privateBalance.refresh();
        combinedBalance.refresh();
        publicMempoolBalance.refresh();
        privateMempoolBalance.refresh();
        fiatBalance.refresh();
        fiatMempoolBalance.refresh();
        fiatPublicBalance.refresh();
        fiatPrivateBalance.refresh();
        fiatCombinedBalance.refresh();
        fiatPublicMempoolBalance.refresh();
        fiatPrivateMempoolBalance.refresh();
    }

    @Subscribe
    public void fiatCurrencySelected(FiatCurrencySelectedEvent event) {
        if(event.getExchangeSource() == ExchangeSource.NONE) {
            for(FiatLabel fiatLabel : List.of(fiatBalance, fiatMempoolBalance, fiatPublicBalance, fiatPrivateBalance, fiatCombinedBalance, fiatPublicMempoolBalance, fiatPrivateMempoolBalance)) {
                fiatLabel.setCurrency(null);
                fiatLabel.setBtcRate(0.0);
            }
        }
    }

    @Subscribe
    public void exchangeRatesUpdated(ExchangeRatesUpdatedEvent event) {
        setFiatBalance(fiatBalance, event.getCurrencyRate(), getWalletForm().getWalletTransactionsEntry().getBalance());
        setFiatBalance(fiatMempoolBalance, event.getCurrencyRate(), getWalletForm().getWalletTransactionsEntry().getMempoolBalance());
        if(combinedBalanceField.isManaged()) {
            setFiatBalance(fiatPublicBalance, event.getCurrencyRate(), publicBalanceValue);
            setFiatBalance(fiatPrivateBalance, event.getCurrencyRate(), privateBalanceValue);
            setFiatBalance(fiatCombinedBalance, event.getCurrencyRate(), combinedBalanceValue);
            setFiatBalance(fiatPublicMempoolBalance, event.getCurrencyRate(), publicMempoolValue);
            setFiatBalance(fiatPrivateMempoolBalance, event.getCurrencyRate(), privateMempoolValue);
        }
    }

    @Subscribe
    public void walletLinkChanged(WalletLinkChangedEvent event) {
        updateLinkedUi();
    }

    @Subscribe
    public void walletHistoryStatus(WalletHistoryStatusEvent event) {
        transactionsTable.updateHistoryStatus(event);

        if(event.getWallet() != null && getWalletForm() != null && getWalletForm().getWallet() == event.getWallet()) {
            String logMessage = event.getStatusMessage();
            if(logMessage == null) {
                if(event instanceof WalletHistoryFinishedEvent) {
                    logMessage = "Finished loading.";
                } else if(event instanceof WalletHistoryFailedEvent) {
                    logMessage = event.getErrorMessage();
                }
            }
            logMessage(logMessage);
        }
    }

    @Subscribe
    public void cormorantStatus(CormorantStatusEvent event) {
        if(event.isFor(walletForm.getWallet())) {
            walletHistoryStatus(new WalletHistoryStatusEvent(walletForm.getWallet(), true, event.getStatus()));
        }
    }

    @Subscribe
    public void bwtSyncStatus(BwtSyncStatusEvent event) {
        walletHistoryStatus(new WalletHistoryStatusEvent(walletForm.getWallet(), true, event.getStatus()));
    }

    @Subscribe
    public void bwtScanStatus(BwtScanStatusEvent event) {
        walletHistoryStatus(new WalletHistoryStatusEvent(walletForm.getWallet(), true, event.getStatus()));
    }

    @Subscribe
    public void bwtShutdown(BwtShutdownEvent event) {
        walletHistoryStatus(new WalletHistoryStatusEvent(walletForm.getWallet(), false));
    }

    @Subscribe
    private void connectionFailed(ConnectionFailedEvent event) {
        walletHistoryStatus(new WalletHistoryStatusEvent(walletForm.getWallet(), false));
    }

    @Subscribe
    public void walletUtxoStatusChanged(WalletUtxoStatusChangedEvent event) {
        if(event.getWallet().equals(getWalletForm().getWallet())) {
            transactionsTable.refresh();
        }
    }

    @Subscribe
    public void includeMempoolOutputsChangedEvent(IncludeMempoolOutputsChangedEvent event) {
        walletHistoryChanged(new WalletHistoryChangedEvent(getWalletForm().getWallet(), getWalletForm().getStorage(), Collections.emptyList(), Collections.emptyList()));
    }

    @Subscribe
    public void loadingLogChanged(LoadingLogChangedEvent event) {
        transactionsMasterDetail.setShowDetailNode(event.isVisible());
    }

    @Subscribe
    public void selectEntry(SelectEntryEvent event) {
        if(event.getWallet().equals(getWalletForm().getWallet()) && event.getEntry().getWalletFunction() == Function.TRANSACTIONS) {
            selectEntry(transactionsTable, transactionsTable.getRoot(), event.getEntry());
        }
    }
}
