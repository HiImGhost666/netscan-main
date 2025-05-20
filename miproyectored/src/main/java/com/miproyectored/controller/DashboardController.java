package com.miproyectored.controller;

import com.miproyectored.export.*;
import com.miproyectored.inventory.InventoryManager;
import com.miproyectored.model.Device;
import com.miproyectored.model.NetworkReport;
import com.miproyectored.scanner.NmapScanner;
import com.miproyectored.util.DataNormalizer;
import com.miproyectored.util.NetworkUtils;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;
import java.util.Map;

public class DashboardController {
    private static final String REPORTS_DIR = "reports";
    private final NmapScanner scanner = new NmapScanner();
    private final JsonExporter jsonExporter = new JsonExporter();
    private final CsvExporter csvExporter = new CsvExporter();
    private final HtmlExporter htmlExporter = new HtmlExporter();
    private final InventoryManager inventoryManager = new InventoryManager();
    private final DataNormalizer dataNormalizer = new DataNormalizer();
    @FXML
    private TextArea outputArea;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Button scanButton;
    @FXML
    private ComboBox<String> networkComboBox;
    @FXML
    private Label statusLabel;
    // Nuevos elementos para las tablas
    @FXML
    private TabPane databaseTabPane;
    @FXML
    private Tab scanReportsTab;
    @FXML
    private Tab devicesTab;
    @FXML
    private Tab devicePortsTab;
    @FXML
    private TableView<InventoryManager.ScanReport> scanReportsTable;
    @FXML
    private TableView<Device> devicesTable;
    @FXML
    private TableView<InventoryManager.DevicePort> devicePortsTable;
    private NetworkReport currentReport;


    @FXML
    public void initialize() {
        // Inicializar comboBox de redes
        List<String> networks = NetworkUtils.detectLocalNetworks();
        if (networks == null || networks.isEmpty()) {
            networkComboBox.getItems().add("scanme.nmap.org");
        } else {
            networkComboBox.getItems().addAll(networks);
        }
        networkComboBox.getSelectionModel().selectFirst();

        // Inicializar tablas de la base de datos
        initializeDatabaseTables();
        loadDatabaseData();
    }

    private void initializeDatabaseTables() {
        // Configurar tabla de ScanReports
        TableColumn<InventoryManager.ScanReport, Long> reportIdCol = new TableColumn<>("ID");
        reportIdCol.setCellValueFactory(new PropertyValueFactory<>("reportId"));

        TableColumn<InventoryManager.ScanReport, String> targetCol = new TableColumn<>("Target");
        targetCol.setCellValueFactory(new PropertyValueFactory<>("target"));

        TableColumn<InventoryManager.ScanReport, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(cellData ->
                new SimpleStringProperty(dataNormalizer.formatTimestamp(cellData.getValue().getTimestamp())));

        TableColumn<InventoryManager.ScanReport, String> engineCol = new TableColumn<>("Engine Info");
        engineCol.setCellValueFactory(new PropertyValueFactory<>("engineInfo"));

        scanReportsTable.getColumns().setAll(reportIdCol, targetCol, dateCol, engineCol);

        // Configurar tabla de Devices
        TableColumn<Device, String> deviceIpCol = new TableColumn<>("IP");
        deviceIpCol.setCellValueFactory(new PropertyValueFactory<>("ip"));

        TableColumn<Device, String> hostnameCol = new TableColumn<>("Hostname");
        hostnameCol.setCellValueFactory(new PropertyValueFactory<>("hostname"));

        TableColumn<Device, String> macCol = new TableColumn<>("MAC");
        macCol.setCellValueFactory(new PropertyValueFactory<>("mac"));

        TableColumn<Device, String> manufacturerCol = new TableColumn<>("Manufacturer");
        manufacturerCol.setCellValueFactory(new PropertyValueFactory<>("manufacturer"));

        TableColumn<Device, String> osCol = new TableColumn<>("OS");
        osCol.setCellValueFactory(new PropertyValueFactory<>("os"));

        TableColumn<Device, String> riskCol = new TableColumn<>("Risk Level");
        riskCol.setCellValueFactory(new PropertyValueFactory<>("riskLevel"));

        devicesTable.getColumns().setAll(deviceIpCol, hostnameCol, macCol, manufacturerCol, osCol, riskCol);

        // Configurar tabla de DevicePorts
        TableColumn<InventoryManager.DevicePort, Long> portIdCol = new TableColumn<>("Port ID");
        portIdCol.setCellValueFactory(new PropertyValueFactory<>("portId"));

        TableColumn<InventoryManager.DevicePort, Long> deviceIdCol = new TableColumn<>("Device ID");
        deviceIdCol.setCellValueFactory(new PropertyValueFactory<>("deviceId"));

        TableColumn<InventoryManager.DevicePort, Integer> portNumCol = new TableColumn<>("Port");
        portNumCol.setCellValueFactory(new PropertyValueFactory<>("portNumber"));

        TableColumn<InventoryManager.DevicePort, String> serviceCol = new TableColumn<>("Service");
        serviceCol.setCellValueFactory(new PropertyValueFactory<>("serviceName"));

        TableColumn<InventoryManager.DevicePort, String> protocolCol = new TableColumn<>("Protocol");
        protocolCol.setCellValueFactory(new PropertyValueFactory<>("protocol"));

        devicePortsTable.getColumns().setAll(portIdCol, deviceIdCol, portNumCol, serviceCol, protocolCol);
    }

    private void loadDatabaseData() {
        scanReportsTable.setItems(FXCollections.observableArrayList(inventoryManager.getAllScanReports()));
        devicesTable.setItems(FXCollections.observableArrayList(inventoryManager.getAllDevices()));
        devicePortsTable.setItems(FXCollections.observableArrayList(inventoryManager.getAllDevicePorts()));
    }

    @FXML
    private void handleScan() {
        String targetNetwork = networkComboBox.getValue();
        if (targetNetwork == null || targetNetwork.isEmpty()) {
            statusLabel.setText("Seleccione una red.");
            return;
        }

        Task<Void> scanTask = new Task<>() {
            @Override
            protected Void call() {
                updateMessage("Escaneando: " + targetNetwork);
                List<Device> detectedDevices = scanner.scan(targetNetwork);

                currentReport = new NetworkReport();
                currentReport.setScannedNetworkTarget(targetNetwork);

                if (detectedDevices != null) {
                    for (Device device : detectedDevices) {
                        currentReport.addDevice(device);
                    }
                }

                long reportId = inventoryManager.saveReport(currentReport);
                if (reportId != -1) {
                    updateMessage("Reporte guardado en BD con ID: " + reportId);
                } else {
                    updateMessage("Fallo al guardar el reporte en la BD.");
                }

                // Actualizar las tablas después del escaneo
                javafx.application.Platform.runLater(() -> loadDatabaseData());

                // Resto del método handleScan existente...
                String safeName = targetNetwork.replaceAll("[^a-zA-Z0-9.-]", "_");
                File dir = new File(REPORTS_DIR);
                if (!dir.exists()) dir.mkdirs();

                String baseName = REPORTS_DIR + File.separator + "reporte_" + safeName;
                jsonExporter.exportReportToFile(currentReport, baseName + ".json");
                csvExporter.exportReportToFile(currentReport, baseName + ".csv");
                htmlExporter.exportReportToFile(currentReport, baseName + ".html");

                StringBuilder output = new StringBuilder();
                output.append("Escaneo finalizado para: ").append(targetNetwork).append("\n");
                output.append("Fecha: ").append(dataNormalizer.formatTimestamp(currentReport.getScanTimestamp())).append("\n");
                output.append("Dispositivos encontrados: ").append(currentReport.getDeviceCount()).append("\n\n");

                if (currentReport.getDevices() != null) {
                    for (Device device : currentReport.getDevices()) {
                        output.append("IP: ").append(device.getIp()).append("\n");
                        if (device.getHostname() != null && !device.getHostname().equals("unknown"))
                            output.append("Hostname: ").append(device.getHostname()).append("\n");
                        if (device.getMac() != null && !device.getMac().equals("UNKNOWN")) {
                            output.append("MAC: ").append(device.getMac());
                            if (device.getManufacturer() != null && !device.getManufacturer().equals("unknown"))
                                output.append(" (" + device.getManufacturer() + ")");
                            output.append("\n");
                        }
                        if (device.getOs() != null && !device.getOs().equals("unknown"))
                            output.append("OS: ").append(device.getOs()).append("\n");
                        if (device.getOpenPorts() != null && !device.getOpenPorts().isEmpty()) {
                            output.append("Puertos abiertos: ").append(device.getOpenPorts().size()).append(" ").append(device.getOpenPorts()).append("\n");
                            if (device.getServices() != null && !device.getServices().isEmpty()) {
                                output.append("Servicios detectados:\n");
                                for (Map.Entry<Integer, String> entry : device.getServices().entrySet()) {
                                    output.append("  - Puerto ").append(entry.getKey()).append("/tcp: ").append(entry.getValue()).append("\n");
                                }
                            }
                        } else {
                            output.append("No se detectaron puertos abiertos.\n");
                        }
                        if (device.getRiskLevel() != null && !device.getRiskLevel().isEmpty())
                            output.append("Nivel de Riesgo: ").append(device.getRiskLevel().toUpperCase()).append("\n");
                        output.append("------------------------------------\n");
                    }
                } else {
                    output.append("No se encontraron dispositivos activos.\n");
                }

                final String finalOutput = output.toString();
                javafx.application.Platform.runLater(() -> outputArea.setText(finalOutput));

                return null;
            }
        };

        progressBar.progressProperty().bind(scanTask.progressProperty());
        statusLabel.textProperty().bind(scanTask.messageProperty());

        new Thread(scanTask).start();
    }

    // Métodos de exportación existentes...
    @FXML
    private void handleExportJson() {
        if (currentReport == null) {
            statusLabel.setText("No hay reporte para exportar.");
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Guardar como JSON");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files", "*.json"));
        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            jsonExporter.exportReportToFile(currentReport, file.getAbsolutePath());
            statusLabel.setText("Exportado a JSON correctamente.");
        }
    }

    @FXML
    private void handleExportCsv() {
        if (currentReport == null) {
            statusLabel.setText("No hay reporte para exportar.");
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Guardar como CSV");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            csvExporter.exportReportToFile(currentReport, file.getAbsolutePath());
            statusLabel.setText("Exportado a CSV correctamente.");
        }
    }

    @FXML
    private void handleExportHtml() {
        if (currentReport == null) {
            statusLabel.setText("No hay reporte para exportar.");
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Guardar como HTML");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("HTML files", "*.html"));
        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            htmlExporter.exportReportToFile(currentReport, file.getAbsolutePath());
            statusLabel.setText("Exportado a HTML correctamente.");
        }
    }
}