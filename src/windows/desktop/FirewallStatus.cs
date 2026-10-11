using System.Reflection;
using System.Runtime.InteropServices;

namespace Camylla.Desktop;

internal static class FirewallStatus
{
    public static string Describe()
    {
        object? policy = null;
        try
        {
            var type = Type.GetTypeFromProgID("HNetCfg.FwPolicy2") ?? throw new InvalidOperationException();
            policy = Activator.CreateInstance(type) ?? throw new InvalidOperationException();
            var profiles = Convert.ToInt32(policy.GetType().InvokeMember("CurrentProfileTypes", BindingFlags.GetProperty, null, policy, null));
            var states = new List<string>();
            foreach (var (flag, name) in new[] { (1, "domain"), (2, "private"), (4, "public") })
                if ((profiles & flag) != 0)
                {
                    var enabled = Convert.ToBoolean(policy.GetType().InvokeMember("FirewallEnabled", BindingFlags.GetProperty, null, policy, [flag]));
                    states.Add($"{name}: {(enabled ? "enabled" : "disabled")}");
                }
            return "Windows Firewall " + string.Join(", ", states) + ". Allow Camylla on the private network for TCP ports 58413 and 58414 and UDP port 41415.";
        }
        catch (Exception) { return "Windows Firewall status unavailable. Check private-network access to TCP ports 58413 and 58414 and UDP port 41415."; }
        finally { if (policy != null && Marshal.IsComObject(policy)) Marshal.FinalReleaseComObject(policy); }
    }
}
