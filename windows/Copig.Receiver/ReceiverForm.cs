using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Security.Cryptography;
using System.Text.Json;

namespace Copig.Receiver;

internal sealed class ReceiverForm : Form
{
    private static readonly Color Pink = Color.FromArgb(170, 49, 95);
    private static readonly Color Blush = Color.FromArgb(255, 238, 245);
    private readonly string stateFolder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Copig");
    private readonly string pin = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
    private readonly Label address = new() { AutoSize = true, MaximumSize = new Size(620, 0) };
    private readonly Label status = new() { AutoSize = true, MaximumSize = new Size(620, 0), Text = "Starting receiver…" };
    private readonly Label destination = new() { AutoSize = true, MaximumSize = new Size(620, 0) };
    private readonly Label transfer = new() { AutoSize = true, Text = "Your saved clips will appear here." };
    private readonly ProgressBar progress = new() { Dock = DockStyle.Fill, Height = 20, Maximum = 1000 };
    private readonly ListBox received = new() { Dock = DockStyle.Fill, HorizontalScrollbar = true };
    private readonly CheckBox enabled = new() { Text = "Receive files", Checked = true, AutoSize = true };
    private readonly NumericUpDown port = new() { Minimum = 1024, Maximum = 65535, Value = ReceiverServer.DefaultPort, Width = 88 };
    private readonly Button restart = Button("Restart");
    private readonly NotifyIcon tray;
    private ReceiverServer? server;
    private bool quitting;
    private string folder;

    public ReceiverForm(bool preview = false)
    {
        SuspendLayout();
        var preferences = LoadPreferences();
        folder = preferences.Folder;
        port.Value = preferences.Port;
        Text = "Copig Receiver";
        Font = new Font("Segoe UI", 10);
        BackColor = Color.FromArgb(255, 248, 250);
        ForeColor = Color.FromArgb(55, 24, 37);
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(560, 600);
        Size = new Size(710, 760);
        Icon = PigIcon();

        var content = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(24), ColumnCount = 1, RowCount = 10 };
        content.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        for (var row = 0; row < 9; row++) content.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        content.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        Controls.Add(content);

        content.Controls.Add(new Label { Text = "Copig", AutoSize = true, Font = new Font("Segoe UI", 28, FontStyle.Bold), ForeColor = Pink }, 0, 0);
        content.Controls.Add(new Label { Text = "From your phone to your computer. Directly over Wi-Fi.", AutoSize = true, Margin = new Padding(0, 0, 0, 16) }, 0, 1);
        var pinCard = new TableLayoutPanel { BackColor = Blush, Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(16), ColumnCount = 1 };
        pinCard.Controls.Add(new Label { Text = $"PIN   {pin}", AutoSize = true, Font = new Font("Consolas", 30, FontStyle.Bold), ForeColor = Pink });
        pinCard.Controls.Add(new Label { Text = "Enter this PIN on your phone. It changes when you quit and restart Copig.", AutoSize = true, MaximumSize = new Size(580, 0) });
        content.Controls.Add(pinCard, 0, 2);
        address.Margin = new Padding(0, 14, 0, 10);
        content.Controls.Add(address, 0, 3);
        var commands = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = true, Margin = new Padding(0) };
        enabled.CheckedChanged += (_, _) => server?.SetAccepting(enabled.Checked);
        commands.Controls.Add(enabled);
        commands.Controls.Add(new Label { Text = "Port", AutoSize = true, Padding = new Padding(8, 6, 0, 0) });
        commands.Controls.Add(port);
        restart.Click += async (_, _) => await Restart();
        commands.Controls.Add(restart);
        var cancel = Button("Cancel transfer");
        cancel.Click += (_, _) => server?.CancelTransfer();
        commands.Controls.Add(cancel);
        var quit = Button("Quit");
        quit.Click += async (_, _) => await Quit();
        commands.Controls.Add(quit);
        content.Controls.Add(commands, 0, 4);
        status.Margin = new Padding(0, 12, 0, 14);
        status.AccessibleName = "Receiver status";
        content.Controls.Add(status, 0, 5);

        var folderRow = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, Margin = new Padding(0) };
        var change = Button("Change folder");
        change.Click += (_, _) => ChangeFolder();
        var open = Button("Open folder");
        open.Click += (_, _) => OpenFolder(folder);
        folderRow.Controls.Add(change); folderRow.Controls.Add(open);
        content.Controls.Add(folderRow, 0, 6);
        destination.Margin = new Padding(0, 2, 0, 14);
        content.Controls.Add(destination, 0, 7);
        var transferRow = new TableLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, ColumnCount = 1 };
        transferRow.Controls.Add(transfer); transferRow.Controls.Add(progress);
        transferRow.Margin = new Padding(0, 0, 0, 12);
        content.Controls.Add(transferRow, 0, 8);
        content.Controls.Add(received, 0, 9);
        received.AccessibleName = "Received files. Double-click a file to show it in Explorer.";
        received.DoubleClick += (_, _) =>
        {
            if (received.SelectedItem is ReceivedFile item && File.Exists(item.Path))
                Process.Start(new ProcessStartInfo("explorer.exe") { Arguments = $"/select,\"{item.Path}\"", UseShellExecute = true });
        };

        var menu = new ContextMenuStrip();
        menu.Items.Add("Open Copig", null, (_, _) => RestoreWindow());
        menu.Items.Add("Open receiving folder", null, (_, _) => OpenFolder(folder));
        menu.Items.Add("Quit", null, async (_, _) => await Quit());
        tray = new NotifyIcon { Text = "Copig Receiver — running", Icon = Icon, ContextMenuStrip = menu, Visible = !preview };
        tray.DoubleClick += (_, _) => RestoreWindow();
        Resize += (_, _) => { if (WindowState == FormWindowState.Minimized) Hide(); };
        FormClosing += (_, e) =>
        {
            if (!quitting && e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true; Hide();
                tray.ShowBalloonTip(3000, "Copig is still receiving", "Open Copig from the pink pig in the taskbar tray. Use Quit to stop receiving.", ToolTipIcon.Info);
            }
        };
        FormClosed += (_, _) => tray.Dispose();
        if (preview)
        {
            address.Text = "Computer: Your Windows computer\nIP address: 192.168.1.25   Port: 53317\nKeep both devices on the same Wi-Fi. Allow access on Private networks if Windows asks.";
            status.Text = "Ready to receive. Enter this PIN on your phone.";
        }
        else Shown += async (_, _) => await Restart();
        UpdateFolder();
        AutoScaleDimensions = new SizeF(96, 96);
        AutoScaleMode = AutoScaleMode.Dpi;
        ResumeLayout(true);
    }

    private async Task Restart()
    {
        restart.Enabled = false;
        try
        {
            if (server is not null) await server.DisposeAsync();
            server = new ReceiverServer(folder, stateFolder, pin, (int)port.Value);
            server.Status += message => OnUi(() => status.Text = message);
            server.Progress += (name, bytes, total) => OnUi(() =>
            {
                transfer.Text = $"{name}  ·  {FormatBytes(bytes)} / {FormatBytes(total)}";
                progress.Value = total == 0 ? 1000 : (int)Math.Clamp(bytes * 1000 / total, 0, 1000);
            });
            server.Received += path => OnUi(() => received.Items.Insert(0, new ReceivedFile(path)));
            await server.StartAsync();
            server.SetAccepting(enabled.Checked);
            address.Text = $"Computer: {Environment.MachineName}\nIP address: {string.Join("  ·  ", ReceiverServer.LocalAddresses())}   Port: {port.Value}\nKeep both devices on the same Wi-Fi. Allow access on Private networks if Windows asks.";
            SavePreferences();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Net.Sockets.SocketException or CryptographicException)
        {
            status.Text = "Could not start: " + error.Message +
                "\nAnother Copig may already be running near the Windows clock. Use Quit in this window, then open the existing pink pig." +
                "\nIf another app such as LocalSend uses this port, change Port to 53318 and press Restart.";
            if (server is not null) { await server.DisposeAsync(); server = null; }
        }
        finally { restart.Enabled = true; }
    }

    private void ChangeFolder()
    {
        using var chooser = new FolderBrowserDialog { Description = "Choose where Copig saves received files", SelectedPath = folder, UseDescriptionForTitle = true };
        if (chooser.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            server?.SetFolder(chooser.SelectedPath);
            folder = chooser.SelectedPath; UpdateFolder(); SavePreferences();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidOperationException)
        { MessageBox.Show(this, error.Message, "Copig", MessageBoxButtons.OK, MessageBoxIcon.Information); }
    }

    private async Task Quit()
    {
        if (quitting) return;
        quitting = true; tray.Visible = false;
        if (server is not null) { await server.DisposeAsync(); server = null; }
        Close();
    }

    private void OnUi(Action update)
    {
        if (IsDisposed || !IsHandleCreated || quitting) return;
        try { BeginInvoke(() => { if (!IsDisposed && !quitting) update(); }); }
        catch (InvalidOperationException) { }
    }
    private void RestoreWindow() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    private void UpdateFolder() => destination.Text = "Saved to: " + folder;
    private static void OpenFolder(string path) { Directory.CreateDirectory(path); Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); }
    private static string FormatBytes(long bytes) => bytes >= 1024 * 1024 ? $"{bytes / (1024d * 1024):0.0} MB" : $"{bytes / 1024d:0.0} KB";
    private static Button Button(string text) => new() { Text = text, AutoSize = true, FlatStyle = FlatStyle.Flat, ForeColor = Pink, BackColor = Blush, Padding = new Padding(6, 2, 6, 2) };
    private Preferences LoadPreferences()
    {
        try
        {
            var preferences = JsonSerializer.Deserialize<Preferences>(File.ReadAllText(Path.Combine(stateFolder, "settings.json")));
            if (preferences is not null && preferences.Port is >= 1024 and <= 65535 && Path.IsPathFullyQualified(preferences.Folder)) return preferences;
        }
        catch (Exception error) when (error is IOException or JsonException or ArgumentException) { }
        return new Preferences(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Copig"), ReceiverServer.DefaultPort);
    }
    private void SavePreferences()
    {
        try { Directory.CreateDirectory(stateFolder); File.WriteAllText(Path.Combine(stateFolder, "settings.json"), JsonSerializer.Serialize(new Preferences(folder, (int)port.Value))); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { status.Text += " Settings could not be saved."; }
    }
    private static Icon PigIcon()
    {
        using var image = new Bitmap(64, 64);
        using var graphics = Graphics.FromImage(image);
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        using var outline = new Pen(Color.FromArgb(75, 25, 45), 3);
        using var skin = new SolidBrush(Color.FromArgb(255, 175, 202));
        using var nose = new SolidBrush(Color.FromArgb(248, 107, 155));
        using var eye = new SolidBrush(outline.Color);
        graphics.FillPolygon(skin, [new(9, 22), new(12, 4), new(28, 17)]);
        graphics.DrawPolygon(outline, [new(9, 22), new(12, 4), new(28, 17)]);
        graphics.FillPolygon(skin, [new(36, 17), new(52, 4), new(55, 22)]);
        graphics.DrawPolygon(outline, [new(36, 17), new(52, 4), new(55, 22)]);
        graphics.FillEllipse(skin, 5, 11, 54, 49); graphics.DrawEllipse(outline, 5, 11, 54, 49);
        graphics.FillEllipse(eye, 18, 27, 5, 5); graphics.FillEllipse(eye, 41, 27, 5, 5);
        graphics.FillEllipse(nose, 20, 37, 25, 16); graphics.DrawEllipse(outline, 20, 37, 25, 16);
        graphics.FillEllipse(eye, 26, 42, 4, 5); graphics.FillEllipse(eye, 36, 42, 4, 5);
        return Icon.FromHandle(image.GetHicon());
    }
    private sealed record Preferences(string Folder, int Port);
    private sealed record ReceivedFile(string Path) { public override string ToString() => System.IO.Path.GetFileName(Path); }
}
