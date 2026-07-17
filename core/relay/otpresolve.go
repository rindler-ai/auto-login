package relay

import (
	"context"

	"github.com/rindler-ai/auto-login/core/otp"
	"github.com/rindler-ai/auto-login/core/protocol"
	"github.com/rindler-ai/auto-login/core/store"
)

// ResolveSecretWithSource behaves exactly like ResolveSecret, except that for the
// code-delivery kinds (email_otp_code / sms_otp_code / manual_code) it can obtain
// the code from an on-device otp.CodeSource when no code was supplied inline.
//
// Resolution order for those kinds:
//  1. a non-empty suppliedCode (a user-typed / already-known code) wins;
//  2. otherwise, if src is non-nil, the code is produced on-device by src
//     (e.g. otp.SourceFromMailbox reads the mailbox and extracts the code);
//  3. otherwise ErrManualCodeRequired.
//
// Only the CODE ever crosses into the relay — src is contractually code-only, so
// the mailbox OAuth token / IMAP credential that produced it never transits. All
// other secret kinds delegate to ResolveSecret unchanged.
func ResolveSecretWithSource(ctx context.Context, rec store.Record, kind protocol.SecretKind, suppliedCode string, src otp.CodeSource) (string, error) {
	switch kind {
	case protocol.SecretEmailOTPCode, protocol.SecretSMSOTPCode, protocol.SecretManualCode:
		if suppliedCode != "" {
			return suppliedCode, nil
		}
		if src != nil {
			code, err := src(ctx)
			if err != nil {
				return "", err
			}
			if code == "" {
				return "", ErrManualCodeRequired
			}
			return code, nil
		}
		return "", ErrManualCodeRequired
	default:
		// username / password / totp_code — no code source involved.
		return ResolveSecret(rec, kind, suppliedCode)
	}
}
