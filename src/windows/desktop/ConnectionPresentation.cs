namespace Camylla.Desktop;

internal static class ConnectionPresentation
{
    internal static (string Title, string Help) Describe(string state, bool fresh, bool paired,
        bool listenerFailed, string? error, uint width, uint height)
    {
        if (listenerFailed)
        {
            var help = error?.Contains("address already in use", StringComparison.OrdinalIgnoreCase) == true ||
                error?.Contains("Only one usage", StringComparison.OrdinalIgnoreCase) == true
                ? "Another app is using a Camylla listener port. Close it, then retry the PC listener."
                : "Check that the PC listener can start and that the firewall allows Camylla. Then retry.";
            return ("PC listener error", help);
        }
        if (error != null) return ("Preview error", "The preview could not display a frame. Check Diagnostics and try reconnecting from the phone.");
        if (fresh) return ($"Streaming · {width} × {height}", "");
        return state switch
        {
            "Streaming" => ("Waiting for fresh video", "The phone is connected, but no recent frame reached the preview. Check the phone and local network."),
            "Recovering" => ("Recovering video", "The phone is reconnecting. The preview and virtual camera stay neutral until a fresh frame arrives."),
            "Negotiating" or "Connecting" or "Authenticating" => ("Connecting phone", "Preparing an authenticated video connection…"),
            "Stopping" => ("Stopping capture", ""),
            "Error" => ("Connection error", "Check the paired phone, local network and PC firewall. Retry from the phone."),
            _ when paired => ("Phone paired · Capture stopped", "Start capture on the phone to stream again."),
            _ => ("Waiting for phone", "Pair a phone to get started."),
        };
    }
}
