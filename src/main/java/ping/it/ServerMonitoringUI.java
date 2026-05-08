package ping.it;

import com.formdev.flatlaf.FlatDarkLaf;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.time.Second;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class ServerMonitoringUI extends JFrame {

    private static final Path SERVERS_FILE = resolveServersFile();

    private static Path resolveServersFile() {
        try {
            Path jar = Paths.get(ServerMonitoringUI.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jar.toString().endsWith(".jar")) {
                // Installed .deb or deployed JAR — write to user config dir (never /usr/share)
                return Paths.get(System.getProperty("user.home"), ".config", "ping-monitor", "servers.conf");
            }
        } catch (Exception ignored) {}
        // IDE / dev: project root
        return Paths.get("servers.conf");
    }

    // IP -> Group, insertion-ordered
    private final Map<String, String> serverGroupMap = new LinkedHashMap<>();

    private final Map<String, TimeSeries> seriesMap = new ConcurrentHashMap<>();
    private final Map<String, Boolean> statusMap = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pingFutures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final JPanel chartGrid;
    private final JTree serverTree;
    private final DefaultMutableTreeNode root;
    private String lastSelectedServer = null;

    public ServerMonitoringUI() {
        super("Ping");
        setIconImage(Toolkit.getDefaultToolkit().getImage(getClass().getResource("/ping.png")));
        scheduler = Executors.newScheduledThreadPool(4);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 600);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        loadServers();

        // --- Sidebar ---
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(200, 0));
        sidebar.setBackground(new Color(45, 45, 45));
        sidebar.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel sideTitle = new JLabel("Servers", SwingConstants.CENTER);
        sideTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));
        sideTitle.setForeground(Color.WHITE);
        sideTitle.setBorder(new EmptyBorder(0, 0, 6, 0));
        sidebar.add(sideTitle, BorderLayout.NORTH);

        // --- Tree ---
        root = new DefaultMutableTreeNode("All Servers");
        serverTree = new JTree(root);
        serverTree.setRootVisible(false);
        serverTree.setBackground(new Color(30, 30, 30));
        serverTree.setForeground(Color.WHITE);
        serverTree.setFont(new Font("Segoe UI", Font.BOLD, 13));

        setupTreeRenderer();
        setupTreeMouseListener();
        rebuildTree();

        sidebar.add(new JScrollPane(serverTree), BorderLayout.CENTER);

        // --- Add Server button pinned at sidebar bottom ---
        JButton addBtn = new JButton("+ Add Server");
        addBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        addBtn.setForeground(Color.WHITE);
        addBtn.setBackground(new Color(46, 120, 46));
        addBtn.setOpaque(true);
        addBtn.setFocusPainted(false);
        addBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(30, 90, 30), 1, true),
                new EmptyBorder(6, 10, 6, 10)));
        addBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addBtn.addActionListener(e -> showAddServerDialog());
        addBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) {
                addBtn.setBackground(new Color(60, 150, 60));
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                addBtn.setBackground(new Color(46, 120, 46));
            }
        });

        JPanel btnWrapper = new JPanel(new BorderLayout());
        btnWrapper.setOpaque(false);
        btnWrapper.setBorder(new EmptyBorder(6, 0, 0, 0));
        btnWrapper.add(addBtn, BorderLayout.CENTER);
        sidebar.add(btnWrapper, BorderLayout.SOUTH);

        add(sidebar, BorderLayout.WEST);

        // --- Chart grid ---
        chartGrid = new JPanel(new GridLayout(0, 2, 12, 12));
        chartGrid.setBorder(new EmptyBorder(10, 10, 10, 10));
        JScrollPane chartScroll = new JScrollPane(chartGrid);
        chartScroll.getVerticalScrollBar().setUnitIncrement(16);
        add(chartScroll, BorderLayout.CENTER);

        serverGroupMap.keySet().forEach(this::startServer);
        updateChartGrid(null);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                scheduler.shutdownNow();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadServers() {
        serverGroupMap.clear();
        if (Files.exists(SERVERS_FILE)) {
            try (BufferedReader br = Files.newBufferedReader(SERVERS_FILE)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int idx = line.indexOf('=');
                    if (idx > 0) {
                        serverGroupMap.put(line.substring(0, idx).trim(),
                                           line.substring(idx + 1).trim());
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            loadDefaults();
            saveServers();
        }
    }

    private void loadDefaults() {
        String[][] defaults = {
            {"172.25.10.1",   "DNS Servers"},
            {"172.26.10.2",   "DNS Servers"},
            {"172.25.10.17",  "SVN Servers"},
            {"172.25.10.2",   "Trunas Servers"},
            {"172.25.10.4",   "Trunas Servers"},
            {"172.25.10.12",  "Trunas Servers"},
            {"172.25.10.15",  "Trunas Servers"},
            {"172.25.10.117", "Trunas Servers"},
            {"172.25.10.7",   "Production Servers"},
            {"172.25.10.63",  "Production Servers"},
            {"172.26.10.5",   "Production Servers"},
            {"172.26.10.44",  "Production Servers"},
            {"172.25.10.55",  "Production Servers"},
            {"172.26.10.56",  "Production Servers"},
            {"172.26.10.57",  "Production Servers"},
            {"172.25.10.115", "IT-Windows"},
        };
        for (String[] e : defaults) serverGroupMap.put(e[0], e[1]);
    }

    private void saveServers() {
        try {
            Path parent = SERVERS_FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter bw = Files.newBufferedWriter(SERVERS_FILE)) {
                bw.write("# Ping server list  —  format: ip=group");
                bw.newLine();
                for (Map.Entry<String, String> e : serverGroupMap.entrySet()) {
                    bw.write(e.getKey() + "=" + e.getValue());
                    bw.newLine();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Server lifecycle (start / stop individual pings)
    // -------------------------------------------------------------------------

    private void startServer(String ip) {
        statusMap.put(ip, true);
        TimeSeries ts = new TimeSeries(ip);
        ts.setMaximumItemAge(300);
        ts.setMaximumItemCount(600);
        seriesMap.put(ip, ts);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> doPingAndUpdate(ip), 0, 5, TimeUnit.SECONDS);
        pingFutures.put(ip, future);
    }

    private void stopServer(String ip) {
        ScheduledFuture<?> future = pingFutures.remove(ip);
        if (future != null) future.cancel(false);
        seriesMap.remove(ip);
        statusMap.remove(ip);
    }

    // -------------------------------------------------------------------------
    // Add / Remove
    // -------------------------------------------------------------------------

    private void showAddServerDialog() {
        JTextField ipField = new JTextField(15);

        // Collect unique groups in sorted order
        List<String> existingGroups = serverGroupMap.values().stream()
                .distinct().sorted().collect(java.util.stream.Collectors.toList());
        JComboBox<String> groupCombo = new JComboBox<>(existingGroups.toArray(new String[0]));
        groupCombo.setEditable(true);
        groupCombo.setPreferredSize(new Dimension(180, groupCombo.getPreferredSize().height));
        // Select first existing group by default so the field is never blank
        if (!existingGroups.isEmpty()) groupCombo.setSelectedIndex(0);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0; panel.add(new JLabel("IP Address:"), gc);
        gc.gridx = 1;              panel.add(ipField, gc);
        gc.gridx = 0; gc.gridy = 1; panel.add(new JLabel("Group:"), gc);
        gc.gridx = 1;              panel.add(groupCombo, gc);

        int result = JOptionPane.showConfirmDialog(this, panel, "Add Server",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        String ip    = ipField.getText().trim();
        String group = Objects.toString(groupCombo.getSelectedItem(), "").trim();

        if (ip.isEmpty() || group.isEmpty()) {
            JOptionPane.showMessageDialog(this, "IP address and group cannot be empty.",
                    "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (serverGroupMap.containsKey(ip)) {
            JOptionPane.showMessageDialog(this, ip + " is already in the list.",
                    "Duplicate", JOptionPane.WARNING_MESSAGE);
            return;
        }

        serverGroupMap.put(ip, group);
        saveServers();
        startServer(ip);
        rebuildTree();
        updateChartGrid(lastSelectedServer);
    }

    private void removeServer(String ip) {
        int choice = JOptionPane.showConfirmDialog(this,
                "Remove  " + ip + "  (" + serverGroupMap.get(ip) + ")?",
                "Confirm Remove", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        stopServer(ip);
        serverGroupMap.remove(ip);
        saveServers();

        if (ip.equals(lastSelectedServer)) lastSelectedServer = null;
        rebuildTree();
        updateChartGrid(lastSelectedServer);
    }

    // -------------------------------------------------------------------------
    // Tree
    // -------------------------------------------------------------------------

    private void rebuildTree() {
        root.removeAllChildren();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : serverGroupMap.entrySet()) {
            grouped.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(entry.getKey());
            for (String srv : entry.getValue()) {
                groupNode.add(new DefaultMutableTreeNode(srv));
            }
            root.add(groupNode);
        }
        DefaultTreeModel model = (DefaultTreeModel) serverTree.getModel();
        model.reload(root);
        expandAllNodes(serverTree, 0, serverTree.getRowCount());
    }

    private void setupTreeRenderer() {
        DefaultTreeCellRenderer base = new DefaultTreeCellRenderer();
        Icon hazard = getScaledWarningIcon(14);

        serverTree.setCellRenderer((tree, value, sel, expanded, leaf, row, hasFocus) -> {
            JLabel lbl = (JLabel) base.getTreeCellRendererComponent(
                    tree, value, sel, expanded, leaf, row, hasFocus);

            if (leaf && value instanceof DefaultMutableTreeNode) {
                String server = value.toString();
                boolean up = statusMap.getOrDefault(server, true);

                JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0)) {
                    @Override public boolean isOpaque() { return false; }
                    @Override public Dimension getPreferredSize() {
                        Dimension d = super.getPreferredSize();
                        return new Dimension(d.width + 20, d.height);
                    }
                };
                JLabel ip = new JLabel(server);
                ip.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                ip.setForeground(up ? Color.WHITE : Color.RED);
                panel.add(ip);
                if (!up && hazard != null) panel.add(new JLabel(hazard));
                return panel;
            }
            lbl.setForeground(Color.LIGHT_GRAY);
            lbl.setIcon(null);
            return lbl;
        });
    }

    private void setupTreeMouseListener() {
        serverTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                TreePath path = serverTree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;

                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();

                if (SwingUtilities.isRightMouseButton(e) && node.isLeaf()) {
                    serverTree.setSelectionPath(path);
                    String ip = node.getUserObject().toString();
                    JPopupMenu menu = new JPopupMenu();
                    JMenuItem removeItem = new JMenuItem("Remove Server");
                    removeItem.setForeground(new Color(220, 80, 80));
                    removeItem.addActionListener(ev -> removeServer(ip));
                    menu.add(removeItem);
                    menu.show(e.getComponent(), e.getX(), e.getY());
                    return;
                }

                if (node.isLeaf()) {
                    String clicked = node.getUserObject().toString();
                    if (clicked.equals(lastSelectedServer)) {
                        serverTree.clearSelection();
                        lastSelectedServer = null;
                        updateChartGrid(null);
                    } else {
                        lastSelectedServer = clicked;
                        serverTree.setSelectionPath(path);
                        updateChartGrid(clicked);
                    }
                } else {
                    lastSelectedServer = null;
                    updateChartGrid(null);
                }
            }
        });
    }

    private void expandAllNodes(JTree tree, int start, int count) {
        for (int i = start; i < count; i++) tree.expandRow(i);
        if (tree.getRowCount() != count) expandAllNodes(tree, count, tree.getRowCount());
    }

    // -------------------------------------------------------------------------
    // Charts
    // -------------------------------------------------------------------------

    private void updateChartGrid(String selected) {
        chartGrid.removeAll();
        if (selected != null) {
            chartGrid.setLayout(new GridLayout(0, 1, 12, 12));
            chartGrid.add(createChartCard(selected));
        } else {
            chartGrid.setLayout(new GridLayout(0, 2, 12, 12));
            serverGroupMap.keySet().forEach(srv -> chartGrid.add(createChartCard(srv)));
        }
        chartGrid.revalidate();
        chartGrid.repaint();
    }

    private JPanel createChartCard(String server) {
        TimeSeriesCollection dataset = new TimeSeriesCollection(seriesMap.get(server));
        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                null, "Time", "Status (1=Up,0=Down)", dataset, false, false, false);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(34, 34, 34));
        plot.setDomainGridlinePaint(new Color(80, 80, 80));
        plot.setRangeGridlinePaint(new Color(80, 80, 80));

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false) {
            @Override
            public Paint getItemPaint(int row, int col) {
                Number v = dataset.getSeries(row).getValue(col);
                return (v != null && v.intValue() == 1)
                        ? new Color(46, 204, 113)
                        : new Color(192, 57, 43);
            }
        };
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));
        plot.setRenderer(renderer);

        DateAxis axis = (DateAxis) plot.getDomainAxis();
        axis.setAutoRange(true);
        axis.setDateFormatOverride(new SimpleDateFormat("HH:mm:ss"));
        axis.setTickLabelPaint(Color.LIGHT_GRAY);

        ChartPanel cp = new ChartPanel(chart);
        cp.setPreferredSize(new Dimension(650, 300));
        cp.setBorder(BorderFactory.createLineBorder(new Color(180, 180, 180), 1, true));

        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(new Color(60, 63, 65));
        JLabel lbl = new JLabel("  " + server);
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 14));
        lbl.setForeground(Color.WHITE);
        lbl.setBorder(new EmptyBorder(6, 6, 6, 6));
        card.add(lbl, BorderLayout.NORTH);
        card.add(cp, BorderLayout.CENTER);
        return card;
    }

    // -------------------------------------------------------------------------
    // Ping
    // -------------------------------------------------------------------------

    private String getPingCommand() {
        if (new File("/usr/bin/ping").exists()) return "/usr/bin/ping";
        if (new File("/bin/ping").exists()) return "/bin/ping";
        return "ping";
    }

    private boolean pingHost(String host) {
        try {
            ProcessBuilder pb = new ProcessBuilder(getPingCommand(), "-c", "1", "-W", "1", host);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getInputStream().transferTo(OutputStream.nullOutputStream());
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void doPingAndUpdate(String server) {
        boolean up = pingHost(server);
        statusMap.put(server, up);
        TimeSeries ts = seriesMap.get(server);
        if (ts != null) ts.addOrUpdate(new Second(new Date()), up ? 1 : 0);
        SwingUtilities.invokeLater(() -> serverTree.repaint());
    }

    // -------------------------------------------------------------------------

    private Icon getScaledWarningIcon(int size) {
        Icon base = UIManager.getIcon("OptionPane.warningIcon");
        if (base == null) return null;
        BufferedImage img = new BufferedImage(base.getIconWidth(), base.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        base.paintIcon(null, g2, 0, 0);
        g2.dispose();
        return new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_SMOOTH));
    }

    public static void main(String[] args) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            try {
                Class<?> shell32 = Class.forName("com.sun.jna.platform.win32.Shell32");
                Class<?> wstring = Class.forName("com.sun.jna.WString");
                Object instance = shell32.getField("INSTANCE").get(null);
                Object ws = wstring.getConstructor(String.class).newInstance("Ping.ServerMonitoringUI");
                shell32.getMethod("SetCurrentProcessExplicitAppUserModelID", wstring).invoke(instance, ws);
            } catch (Exception ignored) {}
        }

        try { UIManager.setLookAndFeel(new FlatDarkLaf()); } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new ServerMonitoringUI().setVisible(true));
    }
}
