using System.Buffers;
using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.Logging;
using BadHttpRequestException = Microsoft.AspNetCore.Http.BadHttpRequestException;

namespace Copig.Receiver;

// LocalSend v2.2 upload protocol: https://github.com/localsend/protocol.
// Kestrel owns HTTP framing, TLS and backpressure; no custom HTTP parser.
internal sealed class ReceiverServer : IAsyncDisposable
{
    public const int DefaultPort = 53317;
    private const long MaximumFileBytes = 8L * 1024 * 1024 * 1024;
    private const long DiskReserve = 64L * 1024 * 1024;
    private static readonly IPAddress MulticastGroup = IPAddress.Parse("224.0.0.167");
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    { MaxDepth = 12, UnmappedMemberHandling = JsonUnmappedMemberHandling.Skip };
    private readonly object gate = new();
    private readonly X509Certificate2 certificate;
    private readonly string pin;
    private readonly int port;
    private readonly CancellationTokenSource lifetime = new();
    private readonly ConcurrentDictionary<string, PinAttempts> attempts = new();
    private readonly ConcurrentDictionary<string, DateTimeOffset> announcements = new();
    private WebApplication? app;
    private UdpClient? udp;
    private Task? discoveryTask;
    private Task? heartbeatTask;
    private TransferSession? session;
    private bool accepting = true;

    public string Fingerprint { get; }
    public string OutputFolder { get; private set; }
    public string Alias => $"Copig · {Environment.MachineName}";
    public bool IsBusy { get { lock (gate) return session is not null; } }
    public event Action<string>? Status;
    public event Action<string, long, long>? Progress;
    public event Action<string>? Received;

    public ReceiverServer(string folder, string stateFolder, string pin, int port = DefaultPort)
    {
        if (pin.Length != 6 || pin.Any(c => c is < '0' or > '9')) throw new ArgumentException("PIN must contain six digits.");
        if (port is < 1024 or > 65535) throw new ArgumentOutOfRangeException(nameof(port));
        this.pin = pin;
        this.port = port;
        OutputFolder = Path.GetFullPath(folder);
        Directory.CreateDirectory(OutputFolder);
        Directory.CreateDirectory(stateFolder);
        certificate = LoadCertificate(Path.Combine(stateFolder, "receiver.pfx"));
        Fingerprint = Convert.ToHexString(SHA256.HashData(certificate.RawData)).ToLowerInvariant();
        // Only remove our own abandoned partial files, never users' recordings.
        foreach (var file in Directory.EnumerateFiles(OutputFolder, ".copig-*.part"))
        {
            try { if (File.GetLastWriteTimeUtc(file) < DateTime.UtcNow.AddDays(-1)) File.Delete(file); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    public async Task StartAsync()
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders(); // Query strings contain PINs/tokens and must never enter request logs.
        builder.WebHost.ConfigureKestrel(options =>
        {
            options.AddServerHeader = false;
            options.Limits.MaxRequestBodySize = MaximumFileBytes;
            options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(15);
            options.Limits.KeepAliveTimeout = TimeSpan.FromSeconds(30);
            options.Limits.MaxConcurrentConnections = 24;
            options.ListenAnyIP(port, listen => { listen.Protocols = HttpProtocols.Http1; listen.UseHttps(certificate); });
        });
        app = builder.Build();
        app.Use(async (context, next) =>
        {
            if (!IsLocalAddress(context.Connection.RemoteIpAddress))
            {
                context.Response.StatusCode = 403;
                return;
            }
            await next(context);
        });
        app.MapGet("/api/localsend/v2/info", () => Results.Json(Info(), JsonOptions));
        app.MapPost("/api/localsend/v2/register", () => Results.Json(Info(), JsonOptions));
        app.MapPost("/api/localsend/v2/prepare-upload", (Func<HttpContext, Task<IResult>>)Prepare);
        app.MapPost("/api/localsend/v2/upload", (Func<HttpContext, Task<IResult>>)Upload);
        app.MapPost("/api/localsend/v2/cancel", (Func<HttpContext, IResult>)Cancel);
        await app.StartAsync(lifetime.Token);
        try
        {
            udp = new UdpClient(AddressFamily.InterNetwork);
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, DefaultPort));
            udp.MulticastLoopback = false;
            udp.Ttl = 1;
            var interfaces = LocalAddresses();
            foreach (var address in interfaces)
                try { udp.JoinMulticastGroup(MulticastGroup, address); } catch (SocketException) { }
            if (interfaces.Count == 0) udp.JoinMulticastGroup(MulticastGroup);
            discoveryTask = ListenDiscovery();
        }
        catch (SocketException)
        {
            udp?.Dispose(); udp = null;
            Status?.Invoke("Automatic discovery is unavailable. Enter this computer's IP address on the phone.");
        }
        heartbeatTask = Heartbeat();
        Status?.Invoke("Ready to receive. Enter the six-digit PIN shown on this computer.");
    }

    public void SetAccepting(bool enabled)
    {
        lock (gate) accepting = enabled;
        if (!enabled) CancelTransfer();
        Status?.Invoke(enabled ? "Ready to receive." : "Receiving paused.");
    }

    public void SetFolder(string path)
    {
        lock (gate)
        {
            if (session is not null) throw new InvalidOperationException("Wait for the transfer to finish before changing folders.");
            var full = Path.GetFullPath(path);
            Directory.CreateDirectory(full);
            OutputFolder = full;
        }
    }

    public void CancelTransfer()
    {
        lock (gate)
        {
            if (session is null) return;
            session.Cancel.Cancel();
            Cleanup(session);
            session = null;
        }
        Status?.Invoke("Transfer cancelled. Completed files are kept.");
    }

    private object Info(bool? announce = null) => new
    {
        alias = Alias, version = "2.2", deviceModel = "Windows", deviceType = "desktop",
        fingerprint = Fingerprint, port, protocol = "https", download = false,
        announce, pin = true,
    };

    private async Task<IResult> Prepare(HttpContext context)
    {
        var address = RemoteAddress(context);
        if (!PinAccepted(address, context.Request.Query["pin"].ToString(), out var limited))
            return Error(limited ? 429 : 401, limited ? "Too many PIN attempts. Wait one minute." : "PIN required or incorrect.");
        PrepareRequest? request;
        try
        {
            if (context.Request.ContentLength is null or > 65_536) return Error(400, "Metadata must include a length of at most 64 KB.");
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
            timeout.CancelAfter(TimeSpan.FromSeconds(15));
            request = await JsonSerializer.DeserializeAsync<PrepareRequest>(context.Request.Body, JsonOptions, timeout.Token);
        }
        catch (Exception error) when (error is JsonException or OperationCanceledException or BadHttpRequestException)
        { return Error(400, "Invalid metadata."); }
        if (request?.Info is null || request.Files is null || request.Files.Count is < 1 or > 16)
            return Error(400, "Provide metadata for 1–16 files.");
        long total = 0;
        foreach (var (id, file) in request.Files)
        {
            if (id.Length is < 1 or > 128 || file is null || file.Id != id || !SafeName(file.FileName) ||
                file.Size is < 0 or > MaximumFileBytes ||
                (file.Sha256 is not null && (file.Sha256.Length != 64 || !file.Sha256.All(Uri.IsHexDigit))))
                return Error(400, "Invalid file name, length, identifier or SHA-256 checksum.");
            total = checked(total + file.Size);
        }
        lock (gate)
        {
            ExpireSession();
            if (!accepting) return Error(403, "Receiving is paused.");
            if (session is not null) return Error(409, "Another transfer is in progress.");
            if (AvailableBytes(OutputFolder) < total + DiskReserve) return Error(403, "Not enough free space in the receiving folder.");
            session = new TransferSession(Token(), address, OutputFolder,
                request.Files.ToDictionary(pair => pair.Key, pair => new PendingFile(pair.Value, Token())));
            Status?.Invoke($"Receiving {request.Files.Count} file(s) from {SafeAlias(request.Info.Alias)}.");
            return Results.Json(new { sessionId = session.Id, files = session.Files.ToDictionary(pair => pair.Key, pair => pair.Value.Token) }, JsonOptions);
        }
    }

    private async Task<IResult> Upload(HttpContext context)
    {
        var sessionId = context.Request.Query["sessionId"].ToString();
        var fileId = context.Request.Query["fileId"].ToString();
        var token = context.Request.Query["token"].ToString();
        if (sessionId.Length == 0 || fileId.Length == 0 || token.Length == 0) return Error(400, "Missing upload parameters.");
        TransferSession current;
        PendingFile file;
        lock (gate)
        {
            ExpireSession();
            if (session is null || session.Id != sessionId || session.Address != RemoteAddress(context) ||
                !session.Files.TryGetValue(fileId, out var pending) || !EqualSecret(pending.Token, token))
                return Error(403, "Invalid session, token or sender address.");
            if (pending.Active || pending.Complete) return Error(409, "This file has already started or finished.");
            if (context.Request.ContentLength is not null && context.Request.ContentLength != pending.Metadata.Size)
                return Error(422, "File length differs from metadata.");
            current = session;
            file = pending;
            file.Active = true;
            file.Part = Path.Combine(current.Folder, $".copig-{Token()}.part");
            current.LastActivity = DateTimeOffset.UtcNow;
        }
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted, current.Cancel.Token, lifetime.Token);
        var buffer = ArrayPool<byte>.Shared.Rent(128 * 1024);
        try
        {
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using (var destination = new FileStream(file.Part, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                128 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                long received = 0;
                var lastUpdate = DateTimeOffset.MinValue;
                while (true)
                {
                    cancellation.CancelAfter(TimeSpan.FromMinutes(2)); // Timeout applies to inactivity, not the whole clip.
                    var count = await context.Request.Body.ReadAsync(buffer.AsMemory(), cancellation.Token);
                    if (count == 0) break;
                    received += count;
                    if (received > file.Metadata.Size) return Error(422, "File is larger than its metadata.");
                    await destination.WriteAsync(buffer.AsMemory(0, count), cancellation.Token);
                    hash.AppendData(buffer, 0, count);
                    current.LastActivity = DateTimeOffset.UtcNow;
                    if (DateTimeOffset.UtcNow - lastUpdate > TimeSpan.FromMilliseconds(150))
                    {
                        if (AvailableBytes(current.Folder) < DiskReserve) return Error(403, "Disk is almost full.");
                        Progress?.Invoke(file.Metadata.FileName!, received, file.Metadata.Size);
                        lastUpdate = DateTimeOffset.UtcNow;
                    }
                }
                if (received != file.Metadata.Size) return Error(422, "File is shorter than its metadata.");
                var checksum = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
                if (file.Metadata.Sha256 is not null && !checksum.Equals(file.Metadata.Sha256, StringComparison.OrdinalIgnoreCase))
                    return Error(422, "SHA-256 checksum mismatch.");
                await destination.FlushAsync(cancellation.Token);
                destination.Flush(true);
            }
            string savedPath;
            lock (gate)
            {
                cancellation.Token.ThrowIfCancellationRequested();
                savedPath = CommitUnique(file.Part!, current.Folder, file.Metadata.FileName!);
                file.Part = null;
                file.Complete = true;
                if (current.Files.Values.All(item => item.Complete) && ReferenceEquals(current, session)) session = null;
            }
            Progress?.Invoke(file.Metadata.FileName!, file.Metadata.Size, file.Metadata.Size);
            Received?.Invoke(savedPath);
            Status?.Invoke($"Saved {Path.GetFileName(savedPath)}.");
            return Results.Ok();
        }
        catch (OperationCanceledException) { return Error(409, "Transfer cancelled or timed out. Send the file again."); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or BadHttpRequestException)
        { return Error(500, "Unable to save the file. Check the receiving folder and free space."); }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
            lock (gate)
            {
                file.Active = false;
                if (file.Part is not null) TryDelete(file.Part);
                file.Part = null;
                current.LastActivity = DateTimeOffset.UtcNow;
            }
        }
    }

    private IResult Cancel(HttpContext context)
    {
        lock (gate)
        {
            if (session is null) return Results.Ok();
            if (session.Id != context.Request.Query["sessionId"].ToString() || session.Address != RemoteAddress(context))
                return Error(403, "Invalid session or sender address.");
            session.Cancel.Cancel();
            Cleanup(session);
            session = null;
        }
        Status?.Invoke("Sender cancelled the transfer.");
        return Results.Ok();
    }

    private bool PinAccepted(string address, string provided, out bool limited)
    {
        var item = attempts.GetOrAdd(address, _ => new PinAttempts());
        lock (item)
        {
            if (DateTimeOffset.UtcNow - item.Start > TimeSpan.FromMinutes(1)) { item.Start = DateTimeOffset.UtcNow; item.Count = 0; }
            limited = item.Count >= 5;
            if (limited) return false;
            if (EqualSecret(pin, provided)) { item.Count = 0; return true; }
            item.Count++;
            return false;
        }
    }

    private async Task ListenDiscovery()
    {
        try
        {
            while (!lifetime.IsCancellationRequested && udp is not null)
            {
                var packet = await udp.ReceiveAsync(lifetime.Token);
                if (packet.Buffer.Length > 8192 || !IsLocalAddress(packet.RemoteEndPoint.Address)) continue;
                try
                {
                    using var document = JsonDocument.Parse(packet.Buffer);
                    if (!document.RootElement.TryGetProperty("announce", out var announce) || announce.ValueKind != JsonValueKind.True) continue;
                    if (document.RootElement.TryGetProperty("fingerprint", out var fingerprint) && fingerprint.GetString() == Fingerprint) continue;
                    var key = packet.RemoteEndPoint.Address.ToString();
                    if (announcements.TryGetValue(key, out var previous) && DateTimeOffset.UtcNow - previous < TimeSpan.FromSeconds(1)) continue;
                    announcements[key] = DateTimeOffset.UtcNow;
                    await Announce(false);
                    await udp.SendAsync(JsonSerializer.SerializeToUtf8Bytes(Info(false), JsonOptions), packet.RemoteEndPoint, lifetime.Token);
                }
                catch (Exception error) when (error is JsonException or InvalidOperationException or SocketException) { }
            }
        }
        catch (Exception error) when (error is OperationCanceledException or ObjectDisposedException or SocketException) { }
    }

    private async Task Heartbeat()
    {
        try
        {
            while (!lifetime.IsCancellationRequested)
            {
                await Announce(true);
                lock (gate) ExpireSession();
                var cutoff = DateTimeOffset.UtcNow.AddMinutes(-2);
                foreach (var pair in announcements.Where(pair => pair.Value < cutoff)) announcements.TryRemove(pair.Key, out _);
                foreach (var pair in attempts.Where(pair => pair.Value.Start < cutoff)) attempts.TryRemove(pair.Key, out _);
                await Task.Delay(TimeSpan.FromSeconds(5), lifetime.Token);
            }
        }
        catch (OperationCanceledException) { }
    }

    private async Task Announce(bool announce)
    {
        if (udp is null) return;
        var packet = JsonSerializer.SerializeToUtf8Bytes(Info(announce), JsonOptions);
        // Send on each active IPv4 interface so Wi-Fi still works when Ethernet/VPN is also connected.
        foreach (var address in LocalAddresses())
        {
            try
            {
                using var sender = new UdpClient(new IPEndPoint(address, 0));
                sender.Ttl = 1;
                sender.MulticastLoopback = false;
                await sender.SendAsync(packet, new IPEndPoint(MulticastGroup, DefaultPort), lifetime.Token);
            }
            catch (SocketException) { }
        }
    }

    public static List<IPAddress> LocalAddresses() => NetworkInterface.GetAllNetworkInterfaces()
        .Where(network => network.OperationalStatus == OperationalStatus.Up && network.NetworkInterfaceType != NetworkInterfaceType.Loopback)
        .SelectMany(network => network.GetIPProperties().UnicastAddresses)
        .Select(item => item.Address).Where(address => address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address) && IsLocalAddress(address)).Distinct().ToList();

    private static bool IsLocalAddress(IPAddress? address)
    {
        if (address is null) return false;
        if (address.AddressFamily != AddressFamily.InterNetwork && !address.IsIPv4MappedToIPv6) return false;
        var bytes = address.MapToIPv4().GetAddressBytes();
        return bytes[0] is 10 or 127 || (bytes[0] == 192 && bytes[1] == 168) ||
            (bytes[0] == 172 && bytes[1] is >= 16 and <= 31) || (bytes[0] == 169 && bytes[1] == 254);
    }

    private void ExpireSession()
    {
        if (session is null || session.Files.Values.Any(file => file.Active)) return;
        var maximumIdle = session.Files.Values.Any(file => file.Complete) ? TimeSpan.FromMinutes(10) : TimeSpan.FromMinutes(1);
        if (DateTimeOffset.UtcNow - session.LastActivity < maximumIdle) return;
        session.Cancel.Cancel(); Cleanup(session); session = null;
        Status?.Invoke("Unused transfer expired. Send the file again.");
    }

    private static bool SafeName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name) || name.Length > 180 || name is "." or ".." || name.EndsWith('.') || name.EndsWith(' ') ||
            name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0 || name.Contains('/') || name.Contains('\\') ||
            name.StartsWith(".copig-", StringComparison.OrdinalIgnoreCase)) return false;
        var stem = name.Split('.')[0].ToUpperInvariant();
        if (stem is "CON" or "PRN" or "AUX" or "NUL" ||
            (stem.Length == 4 && (stem.StartsWith("COM") || stem.StartsWith("LPT")) && stem[3] is >= '1' and <= '9')) return false;
        return true;
    }

    private static string CommitUnique(string partial, string folder, string name)
    {
        var stem = Path.GetFileNameWithoutExtension(name);
        var extension = Path.GetExtension(name);
        for (var number = 0; number < 10_000; number++)
        {
            var target = Path.Combine(folder, number == 0 ? name : $"{stem} ({number}){extension}");
            try { File.Move(partial, target, false); return target; }
            catch (IOException) when (File.Exists(target)) { }
        }
        throw new IOException("Too many copies with the same name.");
    }

    private static long AvailableBytes(string folder)
    {
        try { return new DriveInfo(Path.GetPathRoot(folder)!).AvailableFreeSpace; }
        catch (Exception error) when (error is IOException or ArgumentException) { return 0; }
    }
    private static string RemoteAddress(HttpContext context) => context.Connection.RemoteIpAddress?.MapToIPv4().ToString() ?? "unknown";
    private static string SafeAlias(string? alias) => new string((alias ?? "your phone").Where(c => !char.IsControl(c)).Take(80).ToArray());
    private static string Token() => Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
    private static bool EqualSecret(string expected, string actual) => actual.Length == expected.Length &&
        CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(actual));
    private static IResult Error(int status, string message) => Results.Json(new { message }, statusCode: status);
    private static void TryDelete(string path) { try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { } }
    private static void Cleanup(TransferSession current) { foreach (var file in current.Files.Values) if (file.Part is not null && !file.Active) TryDelete(file.Part); }

    private static X509Certificate2 LoadCertificate(string path)
    {
        if (File.Exists(path))
        {
            try
            {
                // Windows Schannel needs a native key container; ephemeral imported ECDSA keys cannot complete TLS.
                // UserKeySet keeps it in the current user's store and the temporary container is removed on Dispose.
                var existing = X509CertificateLoader.LoadPkcs12FromFile(path, null, X509KeyStorageFlags.UserKeySet);
                if (existing.HasPrivateKey && existing.NotAfter.ToUniversalTime() > DateTime.UtcNow.AddDays(1)) return existing;
                existing.Dispose();
            }
            catch (CryptographicException) { }
        }
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest("CN=Copig Receiver", key, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, false));
        using var created = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
        File.WriteAllBytes(path, created.Export(X509ContentType.Pfx));
        return X509CertificateLoader.LoadPkcs12FromFile(path, null, X509KeyStorageFlags.UserKeySet);
    }

    public async ValueTask DisposeAsync()
    {
        lifetime.Cancel(); CancelTransfer(); udp?.Dispose();
        if (discoveryTask is not null) await discoveryTask;
        if (heartbeatTask is not null) await heartbeatTask;
        if (app is not null) { await app.StopAsync(); await app.DisposeAsync(); }
        certificate.Dispose(); lifetime.Dispose();
    }

    private sealed record PrepareRequest(DeviceInfo? Info, Dictionary<string, FileMetadata>? Files);
    private sealed record DeviceInfo(string? Alias);
    private sealed record FileMetadata(string? Id, string? FileName, long Size, string? FileType, string? Sha256);
    private sealed class PinAttempts { public DateTimeOffset Start = DateTimeOffset.UtcNow; public int Count; }
    private sealed class PendingFile(FileMetadata metadata, string token)
    {
        public FileMetadata Metadata { get; } = metadata;
        public string Token { get; } = token;
        public bool Active; public bool Complete; public string? Part;
    }
    private sealed class TransferSession(string id, string address, string folder, Dictionary<string, PendingFile> files)
    {
        public string Id { get; } = id;
        public string Address { get; } = address;
        public string Folder { get; } = folder;
        public Dictionary<string, PendingFile> Files { get; } = files;
        public CancellationTokenSource Cancel { get; } = new();
        public DateTimeOffset LastActivity = DateTimeOffset.UtcNow;
    }
}
