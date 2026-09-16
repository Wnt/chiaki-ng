# PLE-24 — EGL front-buffer renderer experiment

## Build and selection

The experiment is excluded from the default Android native library. Enable it with:

```bash
cd android
./gradlew assembleDebug -PchiakiAndroidEglRenderer=true
```

The Gradle property sets the default-OFF CMake option
`CHIAKI_ANDROID_EGL_RENDERER`. When enabled, and only when the existing
**Debanding** preference is enabled on Android API 26 or newer, `StreamActivity`
uses the native renderer. Unsupported platforms and failed native initialization
fall back to the existing `GLSurfaceView` renderer.

## Pipeline

The flagged pipeline is:

`MediaCodec -> AImageReader(PRIVATE) -> AHardwareBuffer -> EGLImage -> GLES 3 deband/RCAS -> SurfaceView`

It imports each opaque decoder buffer without a CPU copy and applies the existing
deband/RCAS algorithm directly from `samplerExternalOES`, avoiding the established
renderer's intermediate RGBA framebuffer. The EGL surface requests
`EGL_SINGLE_BUFFER` when `EGL_KHR_mutable_render_buffer` is advertised, enables
`EGL_ANDROID_front_buffer_auto_refresh` when available, and supplies monotonic
timestamps through `eglPresentationTimeANDROID`. It retains a double-buffered EGL
fallback for devices that cannot enter single-buffer mode.

API-26 entry points are weak imports so the minSdk-24 shared library remains
loadable. `ChiakiEglRenderFrame` atrace slices and periodic acquire-to-submit log
lines make the extra stage visible in Perfetto and logcat.

## Device result and decision

Captured on the project Galaxy S22 Ultra (SM-S908B, Exynos 2200, ADB serial
`192.168.1.105:36437`) against the live PS5-466 at `192.168.1.164`. The stream
was 1280x720/60 fps and the panel was in its 1080x2316/120 Hz mode. Each row is
a 15-second Perfetto interval from the same Astro scene. Latency is measured
from the MediaCodec `queueBuffer` slice to the first corresponding HWC present;
the GLSurfaceView row additionally requires the first GL `queueBuffer` that
starts after the decoder queue, so a draw already in flight cannot be credited
with the new decoder image.

The GLSurfaceView capture APK included PLE-47 commit `860f2213`, which removes
the leading newline before the existing shaders' `#version` directive. Without
that independently owned fix the Exynos driver rejects the pre-existing shader
source before a baseline can be measured.

| Path | Frames | Mean | p50 | p90 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Direct SurfaceView | 886 | 7.854 ms | 7.586 ms | 11.754 ms | 13.111 ms |
| GLSurfaceView deband (continuous/default) | 886 | 18.618 ms | 19.206 ms | 21.372 ms | 22.222 ms |
| EGL deband, single/front buffer | 888 | 11.901 ms | 11.992 ms | 15.099 ms | 17.823 ms |

The device advertised `EGL_KHR_mutable_render_buffer`, accepted
`EGL_SINGLE_BUFFER`, and logged `Enabled front-buffer rendering mode`. The EGL
render slice itself averaged 7.060 ms. Relative to the established
GLSurfaceView deband path, the experiment removed 6.717 ms (36.1%) of mean
queue-to-present latency. It remains 4.047 ms slower than decoder-to-SurfaceView,
which is expected because that path has no shader pass.

Artifacts:

- `/home/wnt/gta6/build/dispatch/ple-24/captures/direct.perfetto-trace`
- `/home/wnt/gta6/build/dispatch/ple-24/captures/gl_deband_continuous.perfetto-trace`
- `/home/wnt/gta6/build/dispatch/ple-24/captures/egl_deband.perfetto-trace`
- matching `*_analysis.txt`, `*_logcat.txt`, `*_display.txt`, and `*_screen.png`
  files in the same directory

Decision: **keep behind the default-off build flag**. The measured reduction is
large enough to preserve the experiment for deband/HDR work, but it does not
justify promotion without power, visual-correctness, and broader-device data.
The direct SurfaceView remains the latency baseline when no shader pass is
needed. No S25 was available in this workspace; these numbers must not be
represented as S25 results.
