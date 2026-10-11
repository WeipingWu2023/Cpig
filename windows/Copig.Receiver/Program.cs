using System.Security.Cryptography;

namespace Copig.Receiver;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        var preview = Argument(args, "--render-preview");
        if (preview is not null)
        {
            ApplicationConfiguration.Initialize();
            using var form = new ReceiverForm(preview: true);
            form.ShowInTaskbar = false;
            form.StartPosition = FormStartPosition.Manual;
            form.Location = new System.Drawing.Point(-32000, -32000);
            form.Show();
            Application.DoEvents();
            form.PerformLayout();
            using var bitmap = new System.Drawing.Bitmap(form.Width, form.Height);
            form.DrawToBitmap(bitmap, new System.Drawing.Rectangle(0, 0, form.Width, form.Height));
            bitmap.Save(Path.GetFullPath(preview), System.Drawing.Imaging.ImageFormat.Png);
            form.Hide();
            return;
        }
        if (args.Contains("--headless"))
        {
            RunHeadless(args).GetAwaiter().GetResult();
            return;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new ReceiverForm());
    }

    private static string? Argument(string[] args, string key)
    {
        var index = Array.IndexOf(args, key);
        return index >= 0 && index + 1 < args.Length ? args[index + 1] : null;
    }

    private static async Task RunHeadless(string[] args)
    {
        var folder = Argument(args, "--output") ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Copig");
        var state = Argument(args, "--state") ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Copig");
        var pin = Argument(args, "--pin") ?? RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
        var port = int.Parse(Argument(args, "--port") ?? "53317");
        await using var receiver = new ReceiverServer(folder, state, pin, port);
        receiver.Status += message => Console.WriteLine(message);
        using var stop = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; stop.Cancel(); };
        await receiver.StartAsync();
        Console.WriteLine($"READY {receiver.Fingerprint} {port}");
        try { await Task.Delay(Timeout.Infinite, stop.Token); }
        catch (OperationCanceledException) { }
    }
}
