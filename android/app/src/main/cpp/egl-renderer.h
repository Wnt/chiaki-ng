// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#pragma once

/*
 * JNI entry points for the optional Android AImageReader/EGL renderer live in
 * egl-renderer.cpp.  The implementation is deliberately isolated so an OFF
 * CHIAKI_ANDROID_EGL_RENDERER build neither compiles nor links EGL/GLES code.
 */

