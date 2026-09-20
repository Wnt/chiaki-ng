// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <chiaki/linkwatchdog.h>

CHIAKI_EXPORT void chiaki_link_watchdog_init(ChiakiLinkWatchdog *watchdog, uint32_t now_ms, uint32_t timeout_ms)
{
	watchdog->timeout_ms = timeout_ms;
	watchdog->armed_ms = now_ms;
	watchdog->max_silence_ms = 0;
	watchdog->expired = false;
}

CHIAKI_EXPORT uint32_t chiaki_link_watchdog_silence_ms(const ChiakiLinkWatchdog *watchdog, uint32_t last_receive_ms, uint32_t now_ms)
{
	// Nothing received yet: silence is measured from the moment we started listening.
	uint32_t reference_ms = last_receive_ms ? last_receive_ms : watchdog->armed_ms;
	uint32_t silence_ms = now_ms - reference_ms;
	// The two stamps come from different threads, so `last_receive_ms` can be a
	// little ahead of `now_ms`. Unsigned subtraction would turn that into ~4e9 ms
	// and quit a perfectly healthy stream; an elapsed time that large is always
	// this race rather than a genuine 49-day silence, so read it as zero.
	if(silence_ms > (uint32_t)0x80000000u)
		return 0;
	// Silence cannot predate arming, however early the reference is.
	uint32_t since_armed_ms = now_ms - watchdog->armed_ms;
	if(since_armed_ms > (uint32_t)0x80000000u)
		return 0;
	return silence_ms < since_armed_ms ? silence_ms : since_armed_ms;
}

CHIAKI_EXPORT bool chiaki_link_watchdog_check(ChiakiLinkWatchdog *watchdog, uint32_t last_receive_ms, uint32_t now_ms)
{
	uint32_t silence_ms = chiaki_link_watchdog_silence_ms(watchdog, last_receive_ms, now_ms);
	if(silence_ms > watchdog->max_silence_ms)
		watchdog->max_silence_ms = silence_ms;
	if(watchdog->expired)
		return false;
	if(!watchdog->timeout_ms || silence_ms < watchdog->timeout_ms)
		return false;
	watchdog->expired = true;
	return true;
}
