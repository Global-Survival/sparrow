package com.sparrowwallet.sparrow.wallet;

import com.csvreader.CsvWriter;
import com.google.common.eventbus.Subscribe;
import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.wallet.BlockTransactionHashIndex;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.CoinLabel;
import com.sparrowwallet.sparrow.control.EntryCell;
import com.sparrowwallet.sparrow.control.UtxosChart;
import com.sparrowwallet.sparrow.control.UtxosTreeTable;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

public class UtxosController extends WalletFormController implements Initializable {
    private static final Logger log = LoggerFactory.getLogger(UtxosController.class);

    @FXML
    private UtxosTreeTable utxosTable;

    @FXML
    private Button sendSelected;

    @FXML
    private UtxosChart utxosChart;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        EventManager.get().register(this);
    }

    @Override
    public void initializeView() {
        utxosTable.initialize(getWalletForm().getWalletUtxosEntry());
        utxosChart.initialize(getWalletForm().getWalletUtxosEntry());
        sendSelected.setDisable(true);
        sendSelected.setTooltip(new Tooltip("Send selected UTXOs. Use " + (org.controlsfx.tools.Platform.getCurrent() == org.controlsfx.tools.Platform.OSX ? "Cmd" : "Ctrl") + "+click to select multiple." ));

        utxosTable.getSelectionModel().getSelectedIndices().addListener((ListChangeListener<Integer>) c -> {
            List<Entry> selectedEntries = utxosTable.getSelectionModel().getSelectedCells().stream().map(tp -> tp.getTreeItem().getValue()).collect(Collectors.toList());
            utxosChart.select(selectedEntries);
            updateSendSelected(Config.get().getBitcoinUnit());
        });

        utxosChart.managedProperty().bind(utxosChart.visibleProperty());
        utxosChart.setVisible(Config.get().isShowUtxosChart());
    }

    private void updateSendSelected(BitcoinUnit unit) {
        List<Entry> selectedEntries = utxosTable.getSelectionModel().getSelectedCells().stream().map(tp -> tp.getTreeItem().getValue())
                .filter(entry -> ((HashIndexEntry)entry).isSpendable())
                .collect(Collectors.toList());

        sendSelected.setDisable(selectedEntries.isEmpty());
        long selectedTotal = selectedEntries.stream().mapToLong(Entry::getValue).sum();
        if(selectedTotal > 0) {
            if(unit == null || unit.equals(BitcoinUnit.AUTO)) {
                unit = (selectedTotal >= BitcoinUnit.getAutoThreshold() ? BitcoinUnit.BTC : BitcoinUnit.SATOSHIS);
            }

            if(unit.equals(BitcoinUnit.BTC)) {
                sendSelected.setText("Send Selected (" + CoinLabel.getBTCFormat().format((double)selectedTotal / Transaction.SATOSHIS_PER_BITCOIN) + " BTC)");
            } else {
                sendSelected.setText("Send Selected (" + String.format(Locale.ENGLISH, "%,d", selectedTotal) + " sats)");
            }
        } else {
            sendSelected.setText("Send Selected");
        }
    }

    public void sendSelected(ActionEvent event) {
        List<HashIndexEntry> utxoEntries = utxosTable.getSelectionModel().getSelectedCells().stream()
                .map(tp -> tp.getTreeItem().getValue())
                .filter(e -> e instanceof HashIndexEntry)
                .map(e -> (HashIndexEntry)e)
                .filter(e -> e.getType().equals(HashIndexEntry.Type.OUTPUT) && e.isSpendable())
                .collect(Collectors.toList());

        final List<BlockTransactionHashIndex> spendingUtxos = utxoEntries.stream().map(HashIndexEntry::getHashIndex).collect(Collectors.toList());
        EventManager.get().post(new SendActionEvent(getWalletForm().getWallet(), spendingUtxos));
        Platform.runLater(() -> EventManager.get().post(new SpendUtxoEvent(getWalletForm().getWallet(), spendingUtxos)));
    }

    public void clear(ActionEvent event) {
        utxosTable.getSelectionModel().clearSelection();
    }

    public void exportUtxos(ActionEvent event) {
        Stage window = new Stage();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export UTXOs to CSV");
        fileChooser.setInitialFileName(getWalletForm().getWallet().getFullName() + "-utxos.csv");

        AppServices.moveToActiveWindowScreen(window, 800, 450);
        File file = fileChooser.showSaveDialog(window);
        if(file != null) {
            try(FileOutputStream outputStream = new FileOutputStream(file)) {
                CsvWriter writer = new CsvWriter(outputStream, ',', StandardCharsets.UTF_8);
                writer.writeRecord(new String[] {"Date", "Output", "Address", "Label", "Value"});
                for(Entry entry : getWalletForm().getWalletUtxosEntry().getChildren()) {
                    UtxoEntry utxoEntry = (UtxoEntry)entry;
                    writer.write(utxoEntry.getBlockTransaction().getDate() == null ? "Unconfirmed" : EntryCell.DATE_FORMAT.format(utxoEntry.getBlockTransaction().getDate()));
                    writer.write(utxoEntry.getHashIndex().toString());
                    writer.write(utxoEntry.getAddress().getAddress());
                    writer.write(utxoEntry.getLabel());
                    writer.write(getCoinValue(utxoEntry.getValue()));
                    writer.endRecord();
                }
                writer.close();
            } catch(IOException e) {
                log.error("Error exporting UTXOs as CSV", e);
                AppServices.showErrorDialog("Error exporting UTXOs as CSV", e.getMessage());
            }
        }
    }

    private String getCoinValue(Long value) {
        return BitcoinUnit.BTC.equals(utxosTable.getBitcoinUnit()) ?
                CoinLabel.getBTCFormat().format(value.doubleValue() / Transaction.SATOSHIS_PER_BITCOIN) :
                String.format(Locale.ENGLISH, "%d", value);
    }

    @Subscribe
    public void walletNodesChanged(WalletNodesChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            WalletUtxosEntry walletUtxosEntry = getWalletForm().getWalletUtxosEntry();
            utxosTable.updateAll(walletUtxosEntry);
            utxosChart.update(walletUtxosEntry);
        }
    }

    @Subscribe
    public void walletHistoryChanged(WalletHistoryChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            WalletUtxosEntry walletUtxosEntry = getWalletForm().getWalletUtxosEntry();

            //Will automatically update utxosTable
            walletUtxosEntry.updateUtxos();

            utxosTable.updateHistory(event.getHistoryChangedNodes());
            utxosChart.update(walletUtxosEntry);
        }
    }

    @Subscribe
    public void walletEntryLabelChanged(WalletEntryLabelsChangedEvent event) {
        if(event.getWallet().equals(walletForm.getWallet())) {
            for(Entry entry : event.getEntries()) {
                utxosTable.updateLabel(entry);
            }
            utxosChart.update(getWalletForm().getWalletUtxosEntry());
        }
    }

    @Subscribe
    public void bitcoinUnitChanged(BitcoinUnitChangedEvent event) {
        utxosTable.setBitcoinUnit(getWalletForm().getWallet(), event.getBitcoinUnit());
        utxosChart.setBitcoinUnit(getWalletForm().getWallet(), event.getBitcoinUnit());
        updateSendSelected(event.getBitcoinUnit());
    }

    @Subscribe
    public void walletHistoryStatus(WalletHistoryStatusEvent event) {
        utxosTable.updateHistoryStatus(event);
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
            utxosTable.refresh();
            updateSendSelected(Config.get().getBitcoinUnit());
        }
    }

    @Subscribe
    public void includeMempoolOutputsChangedEvent(IncludeMempoolOutputsChangedEvent event) {
        utxosTable.refresh();
    }

    @Subscribe
    public void utxosChartChanged(UtxosChartChangedEvent event) {
        utxosChart.setVisible(event.isVisible());
    }
}
