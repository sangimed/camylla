namespace Camylla.Receiver;

/// <summary>Explicit cleanup of the two obsolete proof credentials; never imports legacy trust.</summary>
public static class LegacyMigration
{
    private static string LegacyDirectory => Path.Combine(Environment.GetFolderPath(
        Environment.SpecialFolder.LocalApplicationData), "Camylla", "m3-proof");
    private static readonly string[] CredentialFiles = ["development-secret.txt", "receiver-dev-identity.pfx"];
    public static bool CredentialsPresent => CredentialFiles.Any(name => File.Exists(Path.Combine(LegacyDirectory, name)));
    public static bool TakeUpgradeNotice()
    {
        if (!CredentialsPresent) return false;
        var marker = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Camylla", "product-v3", "upgrade-notice-shown.txt");
        if (File.Exists(marker)) return false;
        File.WriteAllText(marker, "Fresh QR pairing required; legacy trust is not imported.");
        return true;
    }
    public static void RemoveCredentials() => RemoveCredentialsFrom(LegacyDirectory);
    internal static void RemoveCredentialsFrom(string directory)
    {
        // Fixed filenames only: leave all logs, evidence and other user files intact.
        foreach (var name in CredentialFiles) File.Delete(Path.Combine(directory, name));
    }
}
