package com.miproyectored.inventory;

import com.miproyectored.model.Device;
import com.miproyectored.model.NetworkReport;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class InventoryManager {
    private static final String DATABASE_URL = "jdbc:sqlite:network_inventory.db";

    public InventoryManager() {
        createTables();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL);
    }

    private void createTables() {
        String sqlScanReports = "CREATE TABLE IF NOT EXISTS ScanReports (" +
                "report_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "scan_target TEXT NOT NULL," +
                "scan_timestamp BIGINT NOT NULL," +
                "scan_engine_info TEXT" +
                ");";

        String sqlDevices = "CREATE TABLE IF NOT EXISTS Devices (" +
                "device_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "report_id INTEGER NOT NULL," +
                "ip_address TEXT NOT NULL," +
                "hostname TEXT," +
                "mac_address TEXT," +
                "manufacturer TEXT," +
                "os_details TEXT," +
                "risk_level TEXT," +
                "FOREIGN KEY (report_id) REFERENCES ScanReports(report_id) ON DELETE CASCADE," +
                "UNIQUE (report_id, ip_address)" +
                ");";

        String sqlDevicePorts = "CREATE TABLE IF NOT EXISTS DevicePorts (" +
                "port_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "device_id INTEGER NOT NULL," +
                "port_number INTEGER NOT NULL," +
                "service_name TEXT," +
                "protocol TEXT DEFAULT 'tcp'," +
                "FOREIGN KEY (device_id) REFERENCES Devices(device_id) ON DELETE CASCADE," +
                "UNIQUE (device_id, port_number, protocol)" +
                ");";

        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(sqlScanReports);
            stmt.execute(sqlDevices);
            stmt.execute(sqlDevicePorts);
            System.out.println("Tablas verificadas/creadas");
        } catch (SQLException e) {
            System.err.println("Error al crear tablas: " + e.getMessage());
        }
    }

    public long saveReport(NetworkReport report) {
        if (report == null) {
            System.err.println("El reporte es nulo, no se puede guardar en la base de datos.");
            return -1; // Indicar fallo
        }

        String sqlInsertReport = "INSERT INTO ScanReports(scan_target, scan_timestamp, scan_engine_info) VALUES(?,?,?)";
        String sqlInsertDevice = "INSERT INTO Devices(report_id, ip_address, hostname, mac_address, manufacturer, os_details, risk_level) VALUES(?,?,?,?,?,?,?)";
        String sqlInsertPort = "INSERT INTO DevicePorts(device_id, port_number, service_name, protocol) VALUES(?,?,?,?)";

        Connection conn = null;
        long reportId = -1;

        try {
            conn = connect();
            conn.setAutoCommit(false); // Iniciar transacción

            // 1. Insertar el ScanReport
            try (PreparedStatement pstmtReport = conn.prepareStatement(sqlInsertReport, Statement.RETURN_GENERATED_KEYS)) {
                pstmtReport.setString(1, report.getScannedNetworkTarget());
                pstmtReport.setLong(2, report.getScanTimestamp());
                pstmtReport.setString(3, report.getScanEngineInfo()); // Puede ser null

                int affectedRows = pstmtReport.executeUpdate();
                if (affectedRows == 0) {
                    throw new SQLException("Fallo al crear el reporte, no se insertaron filas.");
                }

                try (ResultSet generatedKeys = pstmtReport.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        reportId = generatedKeys.getLong(1);
                    } else {
                        throw new SQLException("Fallo al crear el reporte, no se obtuvo ID.");
                    }
                }
            }

            // 2. Insertar cada Device y sus Ports
            if (report.getDevices() != null) {
                for (Device device : report.getDevices()) {
                    long deviceId = -1;
                    try (PreparedStatement pstmtDevice = conn.prepareStatement(sqlInsertDevice, Statement.RETURN_GENERATED_KEYS)) {
                        pstmtDevice.setLong(1, reportId);
                        pstmtDevice.setString(2, device.getIp());
                        pstmtDevice.setString(3, device.getHostname());
                        pstmtDevice.setString(4, device.getMac());
                        pstmtDevice.setString(5, device.getManufacturer());
                        pstmtDevice.setString(6, device.getOs());
                        pstmtDevice.setString(7, device.getRiskLevel()); // Asumiendo que Device tiene getRiskLevel()

                        int affectedDeviceRows = pstmtDevice.executeUpdate();
                        if (affectedDeviceRows == 0) {
                            // Podríamos decidir si continuar con otros dispositivos o hacer rollback total
                            System.err.println("Fallo al insertar el dispositivo " + device.getIp() + ". Se omite este dispositivo.");
                            continue; // Saltar al siguiente dispositivo
                        }

                        try (ResultSet generatedDeviceKeys = pstmtDevice.getGeneratedKeys()) {
                            if (generatedDeviceKeys.next()) {
                                deviceId = generatedDeviceKeys.getLong(1);
                            } else {
                                System.err.println("Fallo al obtener ID para el dispositivo " + device.getIp() + ". Se omiten sus puertos.");
                                continue; // Saltar al siguiente dispositivo
                            }
                        }
                    }

                    if (deviceId != -1 && device.getOpenPorts() != null && !device.getOpenPorts().isEmpty()) {
                        try (PreparedStatement pstmtPort = conn.prepareStatement(sqlInsertPort)) {
                            for (Integer portNumber : device.getOpenPorts()) {
                                pstmtPort.setLong(1, deviceId);
                                pstmtPort.setInt(2, portNumber);
                                String serviceName = (device.getServices() != null) ? device.getServices().get(portNumber) : "Unknown";
                                pstmtPort.setString(3, serviceName);
                                pstmtPort.setString(4, "tcp"); // Asumimos TCP por defecto
                                pstmtPort.addBatch();
                            }
                            pstmtPort.executeBatch();
                        }
                    }
                }
            }

            conn.commit(); // Confirmar todos los cambios si todo fue bien
            System.out.println("Reporte y dispositivos guardados exitosamente en la base de datos. Report ID: " + reportId);

        } catch (SQLException e) {
            System.err.println("Error transaccional al guardar el reporte en la base de datos: " + e.getMessage());
            // e.printStackTrace(); // Para depuración
            if (conn != null) {
                try {
                    System.err.println("Intentando rollback de la transacción.");
                    conn.rollback();
                } catch (SQLException ex) {
                    System.err.println("Error durante el rollback: " + ex.getMessage());
                }
            }
            reportId = -1; // Indicar fallo
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true); // Restaurar auto-commit
                    conn.close();
                } catch (SQLException e) {
                    System.err.println("Error al cerrar la conexión: " + e.getMessage());
                }
            }
        }
        return reportId;
    }

    // Nuevos métodos para obtener datos
    public List<ScanReport> getAllScanReports() {
        String sql = "SELECT report_id, scan_target, scan_timestamp, scan_engine_info FROM ScanReports ORDER BY scan_timestamp DESC";
        List<ScanReport> reports = new ArrayList<>();

        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                reports.add(new ScanReport(
                        rs.getLong("report_id"),
                        rs.getString("scan_target"),
                        rs.getLong("scan_timestamp"),
                        rs.getString("scan_engine_info")
                ));
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener reportes: " + e.getMessage());
        }
        return reports;
    }

    public List<Device> getAllDevices() {
        String sql = "SELECT device_id, report_id, ip_address, hostname, mac_address, manufacturer, os_details, risk_level FROM Devices ORDER BY ip_address";
        List<Device> devices = new ArrayList<>();

        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                Device device = new Device(rs.getString("ip_address"));
                device.setHostname(rs.getString("hostname"));
                device.setMac(rs.getString("mac_address"));
                device.setManufacturer(rs.getString("manufacturer"));
                device.setOs(rs.getString("os_details"));
                device.setRiskLevel(rs.getString("risk_level"));
                devices.add(device);
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener dispositivos: " + e.getMessage());
        }
        return devices;
    }

    public List<DevicePort> getAllDevicePorts() {
        String sql = "SELECT port_id, device_id, port_number, service_name, protocol FROM DevicePorts ORDER BY device_id, port_number";
        List<DevicePort> ports = new ArrayList<>();

        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                ports.add(new DevicePort(
                        rs.getLong("port_id"),
                        rs.getLong("device_id"),
                        rs.getInt("port_number"),
                        rs.getString("service_name"),
                        rs.getString("protocol")
                ));
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener puertos: " + e.getMessage());
        }
        return ports;
    }

    // Clases para representar los datos de las tablas
    public static class ScanReport {
        private final long reportId;
        private final String target;
        private final long timestamp;
        private final String engineInfo;

        public ScanReport(long reportId, String target, long timestamp, String engineInfo) {
            this.reportId = reportId;
            this.target = target;
            this.timestamp = timestamp;
            this.engineInfo = engineInfo;
        }

        // Getters
        public long getReportId() {
            return reportId;
        }

        public String getTarget() {
            return target;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getEngineInfo() {
            return engineInfo;
        }
    }

    public static class DevicePort {
        private final long portId;
        private final long deviceId;
        private final int portNumber;
        private final String serviceName;
        private final String protocol;

        public DevicePort(long portId, long deviceId, int portNumber, String serviceName, String protocol) {
            this.portId = portId;
            this.deviceId = deviceId;
            this.portNumber = portNumber;
            this.serviceName = serviceName;
            this.protocol = protocol;
        }

        // Getters
        public long getPortId() {
            return portId;
        }

        public long getDeviceId() {
            return deviceId;
        }

        public int getPortNumber() {
            return portNumber;
        }

        public String getServiceName() {
            return serviceName;
        }

        public String getProtocol() {
            return protocol;
        }
    }
}