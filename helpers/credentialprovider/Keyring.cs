// The keychain of the OS, without packages (P/Invoke into what the OS has):
//   Linux    the Secret Service (GNOME Keyring, KWallet ≥ 5.97, KeePassXC) through libsecret-1.so.0 — the store IntelliJ uses there too;
//   macOS    the login keychain through Security.framework;
//   Windows  the Credential Manager (generic credentials of the user).
// One item per feed: the key is the normalized URL of the source (SourceKey), the secret the JSON of the user and the password
// (StoredCredentials), so a lookup by the source alone gives both.

using System.Runtime.InteropServices;
using System.Text;

namespace DotNetSupport.CredentialProvider;

public sealed class KeyringException(string message) : Exception(message);

public abstract class Keyring
{
    public const string Label = "C# Project Support NuGet";

    /** Throws [KeyringException] with the reason when the keychain cannot be used (asks it about a key nobody stores). */
    public virtual void Check() => Lookup("io.github.dotnetsupport:check");

    public abstract void Store(string key, StoredCredentials credentials);

    public abstract StoredCredentials? Lookup(string key);

    public abstract void Erase(string key);

    public static Keyring Create()
    {
        // tests only (tools/nuget-auth-feed): a JSON file instead of the keychain, so a restore through the provider runs where none is
        if (Environment.GetEnvironmentVariable("DOTNET_SUPPORT_KEYRING_FILE_FOR_TESTS") is { Length: > 0 } file) return new FileKeyring(file);
        if (OperatingSystem.IsWindows()) return new WindowsKeyring();
        if (OperatingSystem.IsMacOS()) return new MacKeyring();
        return new SecretServiceKeyring();
    }
}

sealed class FileKeyring(string path) : Keyring
{
    private Dictionary<string, StoredCredentials> Read() =>
        File.Exists(path) ? System.Text.Json.JsonSerializer.Deserialize<Dictionary<string, StoredCredentials>>(File.ReadAllText(path)) ?? new() : new();

    public override void Store(string key, StoredCredentials credentials)
    {
        var all = Read();
        all[key] = credentials;
        File.WriteAllText(path, System.Text.Json.JsonSerializer.Serialize(all));
    }

    public override StoredCredentials? Lookup(string key) => Read().GetValueOrDefault(key);

    public override void Erase(string key)
    {
        var all = Read();
        if (all.Remove(key)) File.WriteAllText(path, System.Text.Json.JsonSerializer.Serialize(all));
    }
}

sealed class WindowsKeyring : Keyring
{
    private const uint Generic = 1;
    private const uint PersistLocalMachine = 2;
    private const int ErrorNotFound = 1168;

    [StructLayout(LayoutKind.Sequential)]
    private struct Credential
    {
        public uint Flags;
        public uint Type;
        public IntPtr TargetName;
        public IntPtr Comment;
        public long LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        public IntPtr TargetAlias;
        public IntPtr UserName;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);

    [DllImport("advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredWrite(ref Credential credential, uint flags);

    [DllImport("advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredDelete(string target, uint type, uint flags);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr buffer);

    private static string Target(string key) => "DotNetSupport.NuGet:" + key;

    public override void Store(string key, StoredCredentials credentials)
    {
        var blob = Encoding.UTF8.GetBytes(credentials.ToJson());
        var target = Marshal.StringToHGlobalUni(Target(key));
        var user = Marshal.StringToHGlobalUni(credentials.Username);
        var comment = Marshal.StringToHGlobalUni(Label);
        var data = Marshal.AllocHGlobal(blob.Length);
        try
        {
            Marshal.Copy(blob, 0, data, blob.Length);
            var credential = new Credential
            {
                Type = Generic, TargetName = target, Comment = comment, CredentialBlobSize = (uint)blob.Length, CredentialBlob = data,
                Persist = PersistLocalMachine, UserName = user,
            };
            if (!CredWrite(ref credential, 0)) throw new KeyringException($"The Credential Manager refused to store the password (error {Marshal.GetLastWin32Error()})");
        }
        finally
        {
            Marshal.FreeHGlobal(target); Marshal.FreeHGlobal(user); Marshal.FreeHGlobal(comment); Marshal.FreeHGlobal(data);
        }
    }

    public override StoredCredentials? Lookup(string key)
    {
        if (!CredRead(Target(key), Generic, 0, out var pointer))
        {
            var error = Marshal.GetLastWin32Error();
            return error == ErrorNotFound ? null : throw new KeyringException($"The Credential Manager could not be read (error {error})");
        }
        try
        {
            var credential = Marshal.PtrToStructure<Credential>(pointer);
            var blob = new byte[credential.CredentialBlobSize];
            Marshal.Copy(credential.CredentialBlob, blob, 0, blob.Length);
            return StoredCredentials.FromJson(Encoding.UTF8.GetString(blob));
        }
        finally
        {
            CredFree(pointer);
        }
    }

    public override void Erase(string key)
    {
        if (!CredDelete(Target(key), Generic, 0) && Marshal.GetLastWin32Error() is var error && error != ErrorNotFound)
            throw new KeyringException($"The Credential Manager refused to erase the password (error {error})");
    }
}

sealed class MacKeyring : Keyring
{
    private const string Security = "/System/Library/Frameworks/Security.framework/Security";
    private const string CoreFoundation = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    private const int ItemNotFound = -25300;

    [DllImport(Security)]
    private static extern int SecKeychainFindGenericPassword(IntPtr keychain, uint serviceLength, byte[] service, uint accountLength, byte[] account,
        out uint passwordLength, out IntPtr password, out IntPtr item);

    [DllImport(Security)]
    private static extern int SecKeychainAddGenericPassword(IntPtr keychain, uint serviceLength, byte[] service, uint accountLength, byte[] account,
        uint passwordLength, byte[] password, IntPtr item);

    [DllImport(Security)]
    private static extern int SecKeychainItemModifyAttributesAndData(IntPtr item, IntPtr attributes, uint length, byte[] data);

    [DllImport(Security)]
    private static extern int SecKeychainItemDelete(IntPtr item);

    [DllImport(Security)]
    private static extern int SecKeychainItemFreeContent(IntPtr attributes, IntPtr data);

    [DllImport(CoreFoundation)]
    private static extern void CFRelease(IntPtr item);

    private static readonly byte[] Service = Encoding.UTF8.GetBytes(Label);

    /** The item of [key] and its secret; the item is to be released. Null when there is none. */
    private static (IntPtr Item, string Secret)? Find(string key)
    {
        var account = Encoding.UTF8.GetBytes(key);
        var status = SecKeychainFindGenericPassword(IntPtr.Zero, (uint)Service.Length, Service, (uint)account.Length, account, out var length, out var data, out var item);
        if (status == ItemNotFound) return null;
        if (status != 0) throw new KeyringException($"The keychain could not be read (OSStatus {status})");
        try
        {
            var bytes = new byte[length];
            Marshal.Copy(data, bytes, 0, bytes.Length);
            return (item, Encoding.UTF8.GetString(bytes));
        }
        finally
        {
            SecKeychainItemFreeContent(IntPtr.Zero, data);
        }
    }

    public override void Store(string key, StoredCredentials credentials)
    {
        var secret = Encoding.UTF8.GetBytes(credentials.ToJson());
        var found = Find(key);
        int status;
        if (found is { } existing)
        {
            status = SecKeychainItemModifyAttributesAndData(existing.Item, IntPtr.Zero, (uint)secret.Length, secret);
            CFRelease(existing.Item);
        }
        else
        {
            var account = Encoding.UTF8.GetBytes(key);
            status = SecKeychainAddGenericPassword(IntPtr.Zero, (uint)Service.Length, Service, (uint)account.Length, account, (uint)secret.Length, secret, IntPtr.Zero);
        }
        if (status != 0) throw new KeyringException($"The keychain refused to store the password (OSStatus {status})");
    }

    public override StoredCredentials? Lookup(string key)
    {
        if (Find(key) is not { } found) return null;
        CFRelease(found.Item);
        return StoredCredentials.FromJson(found.Secret);
    }

    public override void Erase(string key)
    {
        if (Find(key) is not { } found) return;
        var status = SecKeychainItemDelete(found.Item);
        CFRelease(found.Item);
        if (status != 0) throw new KeyringException($"The keychain refused to erase the password (OSStatus {status})");
    }
}

/**
 * The Secret Service through libsecret, its `*v_sync` functions (the others take varargs, which P/Invoke cannot pass): the schema
 * `io.github.dotnetsupport.NuGetCredential` with one attribute, `source`. Without a running Secret Service (a server over SSH, WSL, a
 * container) libsecret answers with an error, which goes to the user as it is.
 */
sealed unsafe class SecretServiceKeyring : Keyring
{
    private const string LibSecret = "libsecret-1.so.0";
    private const string GLib = "libglib-2.0.so.0";
    private const string SchemaName = "io.github.dotnetsupport.NuGetCredential";
    private const string Attribute = "source";

    [DllImport(LibSecret)]
    private static extern int secret_password_storev_sync(IntPtr schema, IntPtr attributes, IntPtr collection, IntPtr label, IntPtr password, IntPtr cancellable, out IntPtr error);

    [DllImport(LibSecret)]
    private static extern IntPtr secret_password_lookupv_sync(IntPtr schema, IntPtr attributes, IntPtr cancellable, out IntPtr error);

    [DllImport(LibSecret)]
    private static extern int secret_password_clearv_sync(IntPtr schema, IntPtr attributes, IntPtr cancellable, out IntPtr error);

    [DllImport(LibSecret)]
    private static extern void secret_password_free(IntPtr password);

    [DllImport(GLib)]
    private static extern IntPtr g_hash_table_new(IntPtr hash, IntPtr equal);

    [DllImport(GLib)]
    private static extern int g_hash_table_insert(IntPtr table, IntPtr key, IntPtr value);

    [DllImport(GLib)]
    private static extern void g_hash_table_unref(IntPtr table);

    [DllImport(GLib)]
    private static extern void g_error_free(IntPtr error);

    /** SecretSchema: name, flags, 32 × {name, type}, then reserved fields — 592 bytes on 64 bits; zeroed, one attribute filled. */
    private static readonly IntPtr Schema = MakeSchema();

    private static IntPtr MakeSchema()
    {
        var size = 2 * IntPtr.Size + 32 * 2 * IntPtr.Size + IntPtr.Size + 7 * IntPtr.Size;
        var schema = Marshal.AllocHGlobal(size);
        new Span<byte>((void*)schema, size).Clear();
        Marshal.WriteIntPtr(schema, 0, Marshal.StringToCoTaskMemUTF8(SchemaName));
        Marshal.WriteInt32(schema, IntPtr.Size, 0); // SECRET_SCHEMA_NONE
        Marshal.WriteIntPtr(schema, 2 * IntPtr.Size, Marshal.StringToCoTaskMemUTF8(Attribute));
        Marshal.WriteInt32(schema, 3 * IntPtr.Size, 0); // SECRET_SCHEMA_ATTRIBUTE_STRING
        return schema;
    }

    private static T Call<T>(string key, Func<IntPtr, T> call)
    {
        var library = Load();
        var table = g_hash_table_new(NativeLibrary.GetExport(library, "g_str_hash"), NativeLibrary.GetExport(library, "g_str_equal"));
        var name = Marshal.StringToCoTaskMemUTF8(Attribute);
        var value = Marshal.StringToCoTaskMemUTF8(key);
        try
        {
            g_hash_table_insert(table, name, value);
            return call(table);
        }
        finally
        {
            g_hash_table_unref(table);
            Marshal.FreeCoTaskMem(name);
            Marshal.FreeCoTaskMem(value);
        }
    }

    private static IntPtr Load()
    {
        try
        {
            NativeLibrary.Load(LibSecret);
            return NativeLibrary.Load(GLib);
        }
        catch (DllNotFoundException)
        {
            throw new KeyringException("libsecret is not installed (the package libsecret-1-0 on Debian and Ubuntu, libsecret on Fedora and Arch)");
        }
    }

    private static void ThrowIf(IntPtr error, string what)
    {
        if (error == IntPtr.Zero) return;
        // GError: GQuark domain, gint code, gchar *message
        var message = Marshal.PtrToStringUTF8(Marshal.ReadIntPtr(error, 2 * sizeof(int))) ?? "unknown error";
        g_error_free(error);
        throw new KeyringException($"The Secret Service could not {what}: {message}");
    }

    public override void Store(string key, StoredCredentials credentials)
    {
        var label = Marshal.StringToCoTaskMemUTF8($"{Label}: {key}");
        var secret = Marshal.StringToCoTaskMemUTF8(credentials.ToJson());
        try
        {
            Call(key, table =>
            {
                secret_password_storev_sync(Schema, table, IntPtr.Zero, label, secret, IntPtr.Zero, out var error);
                ThrowIf(error, "store the password");
                return 0;
            });
        }
        finally
        {
            Marshal.FreeCoTaskMem(label);
            // the secret is wiped before the memory goes back
            new Span<byte>((void*)secret, Encoding.UTF8.GetByteCount(credentials.ToJson())).Clear();
            Marshal.FreeCoTaskMem(secret);
        }
    }

    public override StoredCredentials? Lookup(string key) => Call(key, table =>
    {
        var password = secret_password_lookupv_sync(Schema, table, IntPtr.Zero, out var error);
        ThrowIf(error, "read the password");
        if (password == IntPtr.Zero) return null;
        try { return StoredCredentials.FromJson(Marshal.PtrToStringUTF8(password) ?? ""); }
        finally { secret_password_free(password); }
    });

    public override void Erase(string key) => Call(key, table =>
    {
        secret_password_clearv_sync(Schema, table, IntPtr.Zero, out var error);
        ThrowIf(error, "erase the password");
        return 0;
    });
}
