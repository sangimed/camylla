using Camylla.Receiver;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using QRCoder;
using System.Diagnostics;
using System.Runtime.InteropServices.WindowsRuntime;

namespace Camylla.Desktop;

public sealed partial class MainWindow
{
    private PairInvitation? invitation;
    private string phonesSignature = "";
    private long lastPairingUpdate;
    private bool pairDialogShowing;
    private string? displayedPairId;
    private bool pairingReviewTransition;
    private bool replacementDialogShowing;
    private string? reviewedReplacement;

    private async void OnPair(object sender, RoutedEventArgs args)
    {
        try
        {
            if (receiver.Snapshot().State != "Disconnected")
            {
                PairStatus.Text = "Stop phone capture before changing trust."; return;
            }
            invitation = receiver.Trust!.BeginInvitation(LanAddressBox.Text.Trim());
            displayedPairId = invitation.PairId;
            InvitationBox.Text = invitation.Uri;
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(invitation.Uri, QRCodeGenerator.ECCLevel.M);
            using var code = new PngByteQRCode(data);
            var png = code.GetGraphic(6);
            var bitmap = new BitmapImage();
            using var stream = new MemoryStream(png).AsRandomAccessStream();
            await bitmap.SetSourceAsync(stream);
            PairQr.Source = bitmap;
            PairStatus.Text = "Invitation valid for 120 seconds. Scanning does not start capture.";
            ConnectionHelp.Text = $"Listening on {LanAddressBox.Text}:58413 and :58414. {FirewallStatus.Describe()}";
            if (!pairDialogShowing)
            {
                pairDialogShowing = true;
                PairDialog.XamlRoot = ((FrameworkElement)Content).XamlRoot;
                try { await PairDialog.ShowAsync(); }
                finally { pairDialogShowing = false; }
            }
        }
        catch (Exception)
        {
            receiver.Trust!.CancelInvitation(); invitation = null; PairQr.Source = null; InvitationBox.Text = "";
            PairStatus.Text = "Cannot create an invitation. Check the PC LAN address and protected identity.";
        }
    }
    private void OnPairClosed(object sender, object args)
    {
        if (pairingReviewTransition) { invitation = null; PairQr.Source = null; InvitationBox.Text = ""; return; }
        displayedPairId = null;
        receiver.Trust!.CancelInvitation(); invitation = null; PairQr.Source = null; InvitationBox.Text = "";
    }
    private void UpdatePairingUi()
    {
        if (lastPairingUpdate != 0 && Stopwatch.GetElapsedTime(lastPairingUpdate).TotalMilliseconds < 500) return;
        lastPairingUpdate = Stopwatch.GetTimestamp();
        var trust = receiver.Trust!;
        DesktopFingerprint.Text = $"Desktop fingerprint: {trust.DesktopId}";
        UpdateRotationUi();
        if (pairDialogShowing && displayedPairId != null && trust.CompletedPairId == displayedPairId)
        {
            PairDialog.Hide();
            ConnectionHelp.Text = "Phone paired. Tap Start on your phone when ready.";
        }
        var candidate = trust.Candidate;
        if (candidate != null)
        {
            if (candidate.ReplacesId != null && !candidate.Approved && !replacementDialogShowing && reviewedReplacement != candidate.ChallengeId)
                _ = ReviewReplacementAsync(candidate);
            invitation = null; PairQr.Source = null; InvitationBox.Text = "";
            CandidateText.Text = $"Phone: {candidate.Name}\nFingerprint: {candidate.Id}\nChallenge expires at {candidate.Expires.LocalDateTime:T}";
            PairStatus.Text = "Finishing secure pairing automatically…";
        }
        else
        {
            CandidateText.Text = "";
            if (invitation != null && DateTimeOffset.UtcNow >= invitation.Expires)
            {
                trust.CancelInvitation(); invitation = null; PairQr.Source = null; InvitationBox.Text = "";
                PairStatus.Text = "Invitation expired. Create a fresh invitation.";
            }
        }
        var phones = trust.Phones;
        var signature = string.Join(";", phones.Select(p => $"{p.Id}:{p.Revoked}:{p.Updated:O}"));
        if (signature == phonesSignature) return;
        phonesSignature = signature;
        if (phones.Any(p => !p.Revoked)) PairStatus.Text = "Phone paired. Tap Start on the phone when ready.";
        var selected = (TrustedPhonesBox.SelectedItem as ComboBoxItem)?.Tag as string;
        if (phones.Any(p => p.Id == selected && p.Revoked)) selected = null;
        TrustedPhonesBox.Items.Clear();
        foreach (var phone in phones)
        {
            var item = new ComboBoxItem { Content = $"{phone.Name} · {phone.Id[..12]}{(phone.Revoked ? " · revoked" : "")}", Tag = phone.Id };
            TrustedPhonesBox.Items.Add(item);
            if (phone.Id == selected) TrustedPhonesBox.SelectedItem = item;
        }
        if (TrustedPhonesBox.SelectedIndex < 0 && TrustedPhonesBox.Items.Count > 0)
        {
            var index = phones.ToList().FindIndex(p => !p.Revoked);
            TrustedPhonesBox.SelectedIndex = index >= 0 ? index : 0;
        }
    }
    private async Task ReviewReplacementAsync(PairCandidate candidate)
    {
        replacementDialogShowing = true; reviewedReplacement = candidate.ChallengeId;
        try
        {
            pairingReviewTransition = true;
            if (pairDialogShowing) PairDialog.Hide();
            for (var i = 0; pairDialogShowing && i < 20; i++) await Task.Delay(50);
            pairingReviewTransition = false;
            var dialog = new ContentDialog
            {
                XamlRoot = ((FrameworkElement)Content).XamlRoot,
                Title = "Replace this phone's identity?",
                Content = $"Phone: {candidate.Name}\n\nPrevious fingerprint:\n{candidate.ReplacesId}\n\nNew fingerprint:\n{candidate.Id}\n\nApprove only if you requested this change. The previous identity will be revoked.",
                PrimaryButtonText = "Approve replacement", CloseButtonText = "Cancel"
            };
            receiver.Trust!.ApprovePairReplacement(candidate.ChallengeId, await dialog.ShowAsync() == ContentDialogResult.Primary);
        }
        catch (Exception) { ConnectionHelp.Text = "Identity replacement expired or was cancelled. Try a fresh invitation."; }
        finally { pairingReviewTransition = false; replacementDialogShowing = false; }
    }
    private async void OnRevoke(object sender, RoutedEventArgs args)
    {
        if ((TrustedPhonesBox.SelectedItem as ComboBoxItem)?.Tag is not string id) return;
        var dialog = new ContentDialog
        {
            XamlRoot = ((FrameworkElement)Content).XamlRoot,
            Title = "Revoke this phone?", Content = "Its session ends immediately. Connecting again requires a fresh phone-approved pairing.",
            PrimaryButtonText = "Revoke", CloseButtonText = "Cancel"
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary)
            try { receiver.Trust!.Revoke(id); ConnectionHelp.Text = "Phone revoked."; }
            catch (Exception) { ConnectionHelp.Text = "Trust could not be saved. The phone remains trusted."; }
    }
}
