# Draft: wstunnel-testing

## Intent: CLEAR
## Review Required: false
## Status: awaiting-approval

## Context
- UCF VPN Android app (Kotlin) needs to test wstunnel standalone on Android before full refactor
- tproxy mode doesn't work on Android without root → socks5 is the only viable option
- 3-phase progressive testing: socks5 manual → socks5+VPN (tun2socks) → UDP forward+WireGuard

## Decisions Made
1. **Tunnel type enum**: Add `TunnelType` to `WstunnelConfig` (UDP, SOCKS5, HTTP, TCP)
2. **Binary**: wstunnel v10.5.1 ARM64 (confirmed in `scripts/download_wstunnel.sh`)
3. **tun2socks**: Use `hev-socks5-tunnel` (C, ~500KB, supports DNS proxy + fd TUN passthrough)
4. **DNS resolution**: hev-socks5-tunnel handles DNS via SOCKS5 proxy (no leak)
5. **Traffic loop prevention**: Exclude proxy/server IPs from VPN routes or use protect() equivalent

## Momus Review Feedback (Addressed)
- ✅ Phase 1 injection point specified (hardcoded or debug flag in AppConfig)
- ✅ Phase 3 corrected: UDP forward, not TCP
- ✅ Binary version verified (v10.5.1)
- ✅ Proxy auth assumption documented (no credentials needed, matches current UDP mode)
- ✅ Phase 2 blocking issues resolved (DNS, traffic loop, tun2socks integration)

## Approval Gate
- [ ] User approves plan
- [ ] High-accuracy review NOT required (user didn't request it)
- [ ] Plan is decision-complete

## Next Action
Present approval brief → wait for user okay → write final plan
