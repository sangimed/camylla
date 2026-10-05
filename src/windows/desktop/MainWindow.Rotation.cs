using Camylla.Receiver;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Camylla.Desktop;

public sealed partial class MainWindow
{
    private bool rotationDialogShowing;
    private string? reviewedRotation;

    private async void OnRotateDesktop(object sender, RoutedEventArgs args)
    {
        if (rotationDialogShowing || pairDialogShowing) return;
        if ((TrustedPhonesBox.SelectedItem as ComboBoxItem)?.Tag is not string phoneId) return;
        try
        {
            var candidate = await Task.Run(() => receiver.BeginDesktopRotation(phoneId));
            await ReviewRotationAsync(candidate);
        }
        catch (Exception) { ConnectionHelp.Text = "Stop capture and resolve any pending key change before rotating."; }
    }
    private void UpdateRotationUi()
    {
        var candidate = receiver.Trust!.Rotation;
        RotationStatusText.Text = candidate == null ? "" :
            candidate.Approved ? $"Waiting for phone confirmation. Key change expires at {candidate.Expires.LocalDateTime:T}." : "Key change needs confirmation.";
        if (candidate?.Role == "phone" && !candidate.Approved && !rotationDialogShowing && reviewedRotation != candidate.RotationId)
            _ = ReviewRotationAsync(candidate);
    }
    private async Task ReviewRotationAsync(RotationCandidate candidate)
    {
        if (rotationDialogShowing) return;
        rotationDialogShowing = true; reviewedRotation = candidate.RotationId;
        if (pairDialogShowing) { PairDialog.Hide(); await Task.Delay(300); }
        try
        {
            var dialog = new ContentDialog
            {
                XamlRoot = ((FrameworkElement)Content).XamlRoot,
                Title = candidate.Role == "phone" ? "Approve phone key change?" : "Change PC security key?",
                Content = $"Previous fingerprint:\n{candidate.OldId}\n\nNew fingerprint:\n{candidate.NewId}\n\n" +
                    (candidate.Role == "desktop" ? "Confirm the change on the selected phone. Other phones will need fresh pairing." :
                    "Only approve a change you requested on your phone. The previous phone key will be revoked."),
                PrimaryButtonText = "Confirm key change", CloseButtonText = "Cancel"
            };
            var approved = await dialog.ShowAsync() == ContentDialogResult.Primary;
            receiver.Trust!.ApproveRotation(candidate.RotationId, approved);
            ConnectionHelp.Text = approved ? "Confirm the security key change on your phone. Capture stays stopped." : "Key change cancelled.";
        }
        catch (Exception) { ConnectionHelp.Text = "Key change expired or was cancelled. Previous identity is unchanged."; }
        finally { rotationDialogShowing = false; }
    }
}
