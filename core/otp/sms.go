package otp

// SMS one-time codes on the desktop custody client.
//
// This Go client runs on desktop OSes (macOS / Windows / Linux), where there is
// NO OS API to read incoming SMS. So SMS codes take the manual-entry path: the
// user reads the code off their phone and types it into the daemon UI, which
// feeds it in as a supplied code via ManualSMSSource. Only that code is relayed;
// nothing durable is involved, so the invariant is trivially preserved.
//
// Mobile (phase 2, NOT implemented here):
//   - Android will use the SMS User Consent API (or SMS Retriever with an app
//     hash) to read exactly the one verification message the user consents to,
//     extract the code with ExtractCode, and relay only the code.
//   - iOS has no programmatic SMS-read API; it surfaces codes via AutoFill /
//     Password AutoFill from the keyboard, so it stays a user-assisted entry.
//
// Do not add mobile SMS reading to this desktop module.

// ManualSMSSource wraps a user-typed SMS code as a CodeSource. It is a thin,
// intention-revealing alias over SourceFromString for the SMS call sites.
func ManualSMSSource(code string) CodeSource {
	return SourceFromString(code)
}
