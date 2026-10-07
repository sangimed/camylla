namespace Camylla.Desktop;

internal static class ConnectionPresentation
{
    internal static (string Title, string Help) Describe(string state, bool fresh, bool paired,
        bool listenerFailed, string? error, uint width, uint height, string? connectionIssue)
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
        if (state == "Disconnected" && connectionIssue == "connection_lost")
            return ("Recovering connection", "The phone connection ended. Keep capture running on the phone; it will retry automatically.");
        if (state == "Disconnected" && connectionIssue == "trust_rejected")
            return ("Authentication rejected", "Check that this phone is still trusted on the PC and that the phone trusts this PC. Pair again if trust was revoked.");
        if (state == "Disconnected" && connectionIssue == "session_busy")
            return ("Another phone is connected", "Stop the active phone session before connecting another phone.");
        if (state == "Disconnected" && connectionIssue == "connection_rejected")
            return ("Connection rejected", "Check the phone's connection settings and try again.");
        if (state == "Recovering" && connectionIssue == "decoder_failure")
            return ("Decoder error", "The PC could not decode the current video stream. Keep the phone capture running while it reconnects; try another video mode if this repeats.");
        if (state == "Recovering" && connectionIssue is "video_timeout" or "video_failure")
            return ("Recovering video", "The video channel stopped. Check the local network; the phone will request a fresh connection and keyframe.");
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
