package net.runelite.client.launcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-launcher: selects a Jagex account, swaps credentials.properties, then
 * spawns RuneLite as an independent process. Stays alive to tail client.log
 * as an in-window console.
 *
 * Run via:
 *   java -cp client-*-shaded.jar net.runelite.client.launcher.AccountLauncher
 *
 * No -ea or --add-opens needed here; those flags are passed to the RuneLite
 * child process by onLaunch().
 */
public final class AccountLauncher
{
    private static final Path LOG_FILE =
        net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("logs/client.log");

    private static final Path LAUNCHER_SETTINGS =
        net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("settings.json");

    private static final String INSECURE_FLAG = "--insecure-write-credentials";

    private static final String JBIN =
        Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private static final Path JAR = resolveJar();

    private static Path resolveJar()
    {
        try
        {
            return Path.of(AccountLauncher.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        }
        catch (Exception e)
        {
            // Fallback: first entry on the classpath
            return Path.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)[0]);
        }
    }

    // ---- UI components ----
    private final JFrame frame = new JFrame("RLB Launcher");
    private final DefaultListModel<AccountStore.AccountEntry> listModel = new DefaultListModel<>();
    private final JList<AccountStore.AccountEntry> accountList = new JList<>(listModel);
    private final JTextArea console = new JTextArea();
    private final JButton launchBtn    = new JButton("Launch");
    private final JButton stopBtn      = new JButton("Stop");
    private final JButton addBtn       = new JButton("Add Jagex");
    private final JButton addRegularBtn = new JButton("Add Regular");
    private final JButton removeBtn = new JButton("Remove");
    private final JLabel  status    = new JLabel(" ");

    // ---- state ----
    private volatile Process rlProcess;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r ->
    {
        Thread t = new Thread(r, "launcher-bg");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong logPos = new AtomicLong(-1);

    public static void main(String[] args)
    {
        SwingUtilities.invokeLater(() -> new AccountLauncher().show());
    }

    private void show()
    {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(720, 580);
        frame.setLocationRelativeTo(null);

        // ---- button bar ----
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        bar.add(launchBtn);
        bar.add(stopBtn);
        bar.add(addBtn);
        bar.add(addRegularBtn);
        bar.add(removeBtn);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(status);

        // ---- account list ----
        accountList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accountList.setCellRenderer(new AccountCellRenderer());
        accountList.setFixedCellHeight(30);
        JScrollPane listScroll = new JScrollPane(accountList);
        listScroll.setBorder(BorderFactory.createTitledBorder("Jagex Accounts"));
        listScroll.setPreferredSize(new Dimension(0, 180));

        // ---- console ----
        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        console.setBackground(new Color(18, 18, 18));
        console.setForeground(new Color(180, 220, 180));
        console.setCaretColor(new Color(180, 220, 180));
        JScrollPane consoleScroll = new JScrollPane(console);
        TitledBorder cb = BorderFactory.createTitledBorder("Console  (tailing client.log)");
        cb.setTitleColor(Color.GRAY);
        consoleScroll.setBorder(cb);

        // ---- layout ----
        JPanel center = new JPanel(new BorderLayout(4, 6));
        center.setBorder(new EmptyBorder(0, 6, 6, 6));
        center.add(listScroll, BorderLayout.NORTH);
        center.add(consoleScroll, BorderLayout.CENTER);

        frame.add(bar, BorderLayout.NORTH);
        frame.add(center, BorderLayout.CENTER);

        // ---- wiring ----
        launchBtn.addActionListener(e -> onLaunch());
        stopBtn.addActionListener(e -> onStop());
        addBtn.addActionListener(e -> onAddAccount());
        addRegularBtn.addActionListener(e -> onAddRegularAccount());
        removeBtn.addActionListener(e -> onRemove());
        accountList.addListSelectionListener(e -> updateButtons());
        frame.addWindowListener(new WindowAdapter()
        {
            @Override public void windowClosing(WindowEvent e)
            {
                onStop();
                scheduler.shutdownNow();
            }
        });

        reloadList();
        updateButtons();
        scheduler.scheduleAtFixedRate(this::tailLog, 1, 400, TimeUnit.MILLISECONDS);
        frame.setVisible(true);
    }

    // ---- account list ----

    private void reloadList()
    {
        AccountStore.AccountEntry sel = accountList.getSelectedValue();
        listModel.clear();
        AccountStore.load().forEach(listModel::addElement);
        // Restore selection by id if it still exists
        if (sel != null)
        {
            for (int i = 0; i < listModel.size(); i++)
            {
                if (listModel.get(i).id().equals(sel.id()))
                {
                    accountList.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private void updateButtons()
    {
        AccountStore.AccountEntry sel = accountList.getSelectedValue();
        boolean hasSel = sel != null;
        boolean alive  = rlProcess != null && rlProcess.isAlive();
        boolean canLaunch = hasSel && !alive &&
            (!sel.jagex() || AccountStore.hasCredentials(sel.id()));
        launchBtn.setEnabled(canLaunch);
        stopBtn.setEnabled(alive);
        addRegularBtn.setEnabled(!alive);
        removeBtn.setEnabled(hasSel && !alive);
    }

    // ---- actions ----

    private void onLaunch()
    {
        AccountStore.AccountEntry acc = accountList.getSelectedValue();
        if (acc == null) return;

        if (acc.jagex())
        {
            if (!AccountStore.hasCredentials(acc.id()))
            {
                setStatus("No credentials saved for " + acc.name() + " — re-add the account");
                return;
            }
            try
            {
                AccountStore.activateCredentials(acc.id());
            }
            catch (IOException ex)
            {
                setStatus("credentials swap failed: " + ex.getMessage());
                return;
            }
        }

        try
        {
            // inheritIO: RuneLite's stdin/stdout/stderr go to wherever this
            // process inherited from (usually nothing when double-clicked).
            // No pipe FDs connect our process to RuneLite — they appear
            // as independent processes. The console reads client.log instead.
            ProcessBuilder pb = new ProcessBuilder(
                JBIN, "-ea",
                "--add-opens", "java.desktop/com.apple.eawt=ALL-UNNAMED",
                "--add-opens", "java.desktop/com.apple.eawt.event=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", JAR.toString(),
                "--developer-mode"
            );
            pb.inheritIO();
            rlProcess = pb.start();
            logPos.set(-1); // reset tail so we pick up from "now"
            setStatus("Launched RuneLite for " + acc.name());
            updateButtons();
            scheduler.schedule(this::pollDeath, 2, TimeUnit.SECONDS);
        }
        catch (IOException ex)
        {
            setStatus("launch failed: " + ex.getMessage());
        }
    }

    private void pollDeath()
    {
        Process p = rlProcess;
        if (p == null) return;
        if (!p.isAlive())
        {
            SwingUtilities.invokeLater(() ->
            {
                setStatus("RuneLite exited (code " + p.exitValue() + ")");
                updateButtons();
            });
        }
        else
        {
            scheduler.schedule(this::pollDeath, 2, TimeUnit.SECONDS);
        }
    }

    private void onStop()
    {
        Process p = rlProcess;
        if (p != null && p.isAlive())
        {
            p.destroy();
            setStatus("stop signal sent");
        }
        SwingUtilities.invokeLater(this::updateButtons);
    }

    private void onRemove()
    {
        AccountStore.AccountEntry acc = accountList.getSelectedValue();
        if (acc == null) return;
        int c = JOptionPane.showConfirmDialog(frame,
            "Remove '" + acc.name() + "'?\n(Saved credentials file will also be deleted.)",
            "Remove Account", JOptionPane.YES_NO_OPTION);
        if (c != JOptionPane.YES_OPTION) return;
        // Delete saved credentials directory
        Path dir = AccountStore.ACCOUNTS_DIR.resolve(acc.id());
        try { deleteDir(dir); } catch (IOException ignored) {}
        List<AccountStore.AccountEntry> list = AccountStore.load();
        list.removeIf(a -> a.id().equals(acc.id()));
        AccountStore.save(list);
        reloadList();
        updateButtons();
    }

    private static void deleteDir(Path dir) throws IOException
    {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir))
        {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    // ---- add account flow ----

    private void onAddAccount()
    {
        String name = (String) JOptionPane.showInputDialog(frame,
            "Display name for this Jagex account:", "Add Account",
            JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (name == null || name.isBlank()) return;
        name = name.trim();

        String id = UUID.randomUUID().toString().substring(0, 8);
        String finalName = name;
        String finalId   = id;

        // Inject --insecure-write-credentials into launcher settings.json so
        // the Jagex Launcher writes plaintext creds we can capture.
        try { injectLauncherFlag(INSECURE_FLAG); }
        catch (IOException ex)
        {
            setStatus("Could not update launcher settings: " + ex.getMessage());
            return;
        }

        // Snapshot host's current ACTIVE_CREDS bytes so we can restore them
        // after capture — adding a Jagex account must not change which
        // account host's RuneLite would auto-launch into next time.
        byte[] credsSnapshot = null;
        try
        {
            if (Files.exists(AccountStore.ACTIVE_CREDS))
                credsSnapshot = Files.readAllBytes(AccountStore.ACTIVE_CREDS);
        }
        catch (IOException ignored) {}
        final byte[] fCredsSnapshot = credsSnapshot;

        // Snapshot current mtime so we detect when a FRESH file is written.
        long baseMtime = 0L;
        try
        {
            if (Files.exists(AccountStore.ACTIVE_CREDS))
                baseMtime = Files.getLastModifiedTime(AccountStore.ACTIVE_CREDS).toMillis();
        }
        catch (IOException ignored) {}
        long fBase = baseMtime;

        // Launch Jagex Launcher (OS-specific — see launchJagexLauncher).
        try { launchJagexLauncher(); }
        catch (Exception ex)
        {
            setStatus("Could not launch Jagex Launcher: " + ex.getMessage());
            cleanupInsecureFlag();
            return;
        }

        // ---- waiting dialog ----
        JDialog dlg = new JDialog(frame, "Add Jagex Account — " + finalName, true);
        dlg.setSize(420, 180);
        dlg.setLocationRelativeTo(frame);
        dlg.setLayout(new BorderLayout(8, 8));

        JLabel info = new JLabel(
            "<html><center>Jagex Launcher is open.<br>" +
            "Select <b>" + finalName + "</b> and click <b>Play</b>.<br><br>" +
            "Credentials will be saved automatically.</center></html>",
            SwingConstants.CENTER);
        info.setBorder(new EmptyBorder(16, 16, 8, 16));

        JLabel waiting = new JLabel("  Waiting for Jagex Launcher…", SwingConstants.CENTER);
        waiting.setForeground(new Color(100, 160, 230));
        waiting.setBorder(new EmptyBorder(0, 8, 8, 8));

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(ev -> dlg.dispose());

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        south.add(cancel);

        dlg.add(info, BorderLayout.CENTER);
        dlg.add(waiting, BorderLayout.NORTH);
        dlg.add(south, BorderLayout.SOUTH);

        // Poll for credentials on a background thread
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "creds-poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(() ->
        {
            if (!dlg.isShowing())
            {
                poller.shutdownNow();
                cleanupInsecureFlag();
                return;
            }
            try
            {
                if (!Files.exists(AccountStore.ACTIVE_CREDS)) return;
                long mtime = Files.getLastModifiedTime(AccountStore.ACTIVE_CREDS).toMillis();
                if (mtime <= fBase) return;

                // Fresh credentials written — save to per-account dir,
                // restore host's pre-capture state, register, clean flag.
                AccountStore.saveCredentials(finalId, AccountStore.ACTIVE_CREDS);

                // Restore (or remove) ACTIVE_CREDS so host's next launch
                // picks the same account it would have before the capture.
                try
                {
                    if (fCredsSnapshot != null)
                        Files.write(AccountStore.ACTIVE_CREDS, fCredsSnapshot);
                    else
                        Files.deleteIfExists(AccountStore.ACTIVE_CREDS);
                }
                catch (IOException ignored) {}

                List<AccountStore.AccountEntry> list = AccountStore.load();
                list.add(new AccountStore.AccountEntry(finalId, finalName, true));
                AccountStore.save(list);
                cleanupInsecureFlag();
                poller.shutdownNow();

                SwingUtilities.invokeLater(() ->
                {
                    dlg.dispose();
                    reloadList();
                    setStatus("Added: " + finalName);
                });
            }
            catch (IOException ex)
            {
                SwingUtilities.invokeLater(() ->
                    waiting.setText("  Error: " + ex.getMessage()));
            }
        }, 2, 2, TimeUnit.SECONDS);

        dlg.setVisible(true);
        poller.shutdownNow(); // clean up if dialog was cancelled without creds
    }

    private void onAddRegularAccount()
    {
        String name = (String) JOptionPane.showInputDialog(frame,
            "Display name for this regular (non-Jagex) account:", "Add Regular Account",
            JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (name == null || name.isBlank()) return;
        name = name.trim();

        String id = UUID.randomUUID().toString().substring(0, 8);
        List<AccountStore.AccountEntry> list = AccountStore.load();
        list.add(new AccountStore.AccountEntry(id, name, false));
        AccountStore.save(list);
        reloadList();
        setStatus("Added: " + name);
    }

    // ---- launcher settings.json helpers ----

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static JsonObject readLauncherSettings()
    {
        if (!Files.exists(LAUNCHER_SETTINGS)) return new JsonObject();
        try
        {
            String raw = Files.readString(LAUNCHER_SETTINGS);
            JsonElement el = JsonParser.parseString(raw);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        }
        catch (Exception e) { return new JsonObject(); }
    }

    private static void writeLauncherSettings(JsonObject obj) throws IOException
    {
        Files.createDirectories(LAUNCHER_SETTINGS.getParent());
        Path tmp = LAUNCHER_SETTINGS.resolveSibling("settings.json.tmp");
        Files.writeString(tmp, GSON.toJson(obj));
        java.nio.file.Files.move(tmp, LAUNCHER_SETTINGS,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static void cleanupInsecureFlag()
    {
        try { removeLauncherFlag(INSECURE_FLAG); } catch (IOException ignored) {}
        if (isMac())
        {
            try { new ProcessBuilder("launchctl", "unsetenv", "RUNELITE_ARGS").start().waitFor(); }
            catch (Exception ignored) {}
        }
        // On Linux the env var is scoped to the wine ProcessBuilder we
        // started, so it dies with that process — no system-level cleanup.
    }

    private static boolean isMac()
    {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }

    private static boolean isLinux()
    {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    private static boolean isWindows()
    {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    private static Path findWindowsJagexLauncherExe()
    {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank())
            localAppData = Path.of(System.getProperty("user.home"), "AppData", "Local").toString();
        String pf   = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        if (pf == null || pf.isBlank())   pf   = "C:\\Program Files";
        if (pf86 == null || pf86.isBlank()) pf86 = "C:\\Program Files (x86)";

        Path[] candidates = {
            Path.of(localAppData, "Jagex Launcher", "JagexLauncher.exe"),
            Path.of(pf,   "Jagex Launcher", "JagexLauncher.exe"),
            Path.of(pf86, "Jagex Launcher", "JagexLauncher.exe"),
        };
        for (Path p : candidates)
            if (Files.exists(p)) return p;
        return candidates[0]; // first candidate, for the error message
    }

    /**
     * Launch the Jagex Launcher with --insecure-write-credentials in scope.
     * Mac uses the bundled .app via {@code open -a}, with launchctl setting
     * RUNELITE_ARGS so the launchd-spawned child can see it. Linux uses Wine
     * and inherits the env directly via {@link ProcessBuilder#environment()}.
     * The Wine path is taken from JAGEX_LAUNCHER_EXE — the rlb-capture
     * container bakes the .exe in and points this at it.
     */
    private static void launchJagexLauncher() throws IOException, InterruptedException
    {
        if (isMac())
        {
            new ProcessBuilder("launchctl", "setenv", "RUNELITE_ARGS", INSECURE_FLAG)
                .start().waitFor();
            new ProcessBuilder("open", "-a", "Jagex Launcher").start();
            return;
        }
        if (isLinux())
        {
            String exe = System.getenv("JAGEX_LAUNCHER_EXE");
            if (exe == null || exe.isBlank())
            {
                throw new IOException("JAGEX_LAUNCHER_EXE env var not set — " +
                    "running outside the rlb-capture container?");
            }
            ProcessBuilder pb = new ProcessBuilder("wine", exe);
            pb.environment().put("RUNELITE_ARGS", INSECURE_FLAG);
            pb.inheritIO();
            pb.start();
            return;
        }
        if (isWindows())
        {
            String override = System.getenv("JAGEX_LAUNCHER_EXE");
            Path exe = (override != null && !override.isBlank())
                ? Path.of(override)
                : findWindowsJagexLauncherExe();
            if (!Files.exists(exe))
            {
                throw new IOException("Jagex Launcher not found at " + exe +
                    " - install Jagex Launcher or set JAGEX_LAUNCHER_EXE");
            }
            ProcessBuilder pb = new ProcessBuilder(exe.toString());
            pb.environment().put("RUNELITE_ARGS", INSECURE_FLAG);
            pb.inheritIO();
            pb.start();
            return;
        }
        throw new IOException("Add Jagex not supported on " + System.getProperty("os.name"));
    }

    private static void injectLauncherFlag(String flag) throws IOException
    {
        JsonObject obj = readLauncherSettings();
        JsonArray args = obj.has("clientArguments")
            ? obj.getAsJsonArray("clientArguments")
            : new JsonArray();

        // idempotent — don't add twice
        for (JsonElement e : args)
            if (flag.equals(e.getAsString())) { obj.add("clientArguments", args); writeLauncherSettings(obj); return; }

        args.add(flag);
        obj.add("clientArguments", args);
        writeLauncherSettings(obj);
    }

    private static void removeLauncherFlag(String flag) throws IOException
    {
        if (!Files.exists(LAUNCHER_SETTINGS)) return;
        JsonObject obj = readLauncherSettings();
        if (!obj.has("clientArguments")) return;
        JsonArray src = obj.getAsJsonArray("clientArguments");
        JsonArray filtered = new JsonArray();
        for (JsonElement e : src)
            if (!flag.equals(e.getAsString())) filtered.add(e);
        obj.add("clientArguments", filtered);
        writeLauncherSettings(obj);
    }

    // ---- log tail ----

    private void tailLog()
    {
        try
        {
            if (!Files.exists(LOG_FILE)) return;
            long size = Files.size(LOG_FILE);
            long pos  = logPos.get();

            if (pos < 0)
            {
                // First tick after a launch: anchor to current end so we only
                // show output from this session onward.
                logPos.set(size);
                return;
            }
            if (size < pos) { logPos.set(0); pos = 0; } // log rotated
            if (size == pos) return;

            int toRead = (int) Math.min(size - pos, 16_384);
            byte[] buf = new byte[toRead];
            try (RandomAccessFile raf = new RandomAccessFile(LOG_FILE.toFile(), "r"))
            {
                raf.seek(pos);
                int n = raf.read(buf, 0, toRead);
                if (n <= 0) return;
                logPos.addAndGet(n);
                String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                SwingUtilities.invokeLater(() ->
                {
                    console.append(text);
                    // Auto-scroll only if already at the bottom
                    JScrollBar sb = ((JScrollPane) console.getParent().getParent()).getVerticalScrollBar();
                    if (sb.getValue() >= sb.getMaximum() - sb.getVisibleAmount() - 40)
                        console.setCaretPosition(console.getDocument().getLength());
                });
            }
        }
        catch (IOException ignored) {}
    }

    // ---- helpers ----

    private void setStatus(String msg)
    {
        SwingUtilities.invokeLater(() -> status.setText(msg));
    }

    // ---- cell renderer ----

    private static final class AccountCellRenderer extends DefaultListCellRenderer
    {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
            int index, boolean isSelected, boolean hasFocus)
        {
            super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
            if (value instanceof AccountStore.AccountEntry a)
            {
                if (a.jagex())
                {
                    boolean hasCreds = AccountStore.hasCredentials(a.id());
                    setText("[J] " + a.name() + (hasCreds ? "" : "  ⚠ no credentials"));
                    if (!isSelected)
                        setForeground(hasCreds ? new Color(80, 150, 230) : Color.GRAY);
                }
                else
                {
                    setText("[R] " + a.name());
                    if (!isSelected)
                        setForeground(new Color(120, 200, 120));
                }
            }
            setBorder(new EmptyBorder(4, 8, 4, 8));
            return this;
        }
    }
}
