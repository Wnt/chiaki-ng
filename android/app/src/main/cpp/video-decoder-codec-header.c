// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder-codec-header.h"

bool android_chiaki_sample_is_codec_header(bool h265, const uint8_t *buf, size_t buf_size)
{
	size_t nal = 0;
	if(buf_size >= 4 && buf[0] == 0 && buf[1] == 0 && buf[2] == 0 && buf[3] == 1)
		nal = 4;
	else if(buf_size >= 3 && buf[0] == 0 && buf[1] == 0 && buf[2] == 1)
		nal = 3;
	if(nal == 0 || nal >= buf_size)
		return false;
	if(h265)
	{
		unsigned int type = (buf[nal] >> 1) & 0x3f;
		return type == 32 || type == 33 || type == 34;
	}
	unsigned int type = buf[nal] & 0x1f;
	return type == 7 || type == 8;
}
