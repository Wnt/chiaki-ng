// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "egl-renderer.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/trace.h>
#include <jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <pthread.h>
#include <time.h>

#include <cstdint>
#include <cstdlib>
#include <cstring>

#ifndef EGL_MUTABLE_RENDER_BUFFER_BIT_KHR
#define EGL_MUTABLE_RENDER_BUFFER_BIT_KHR 0x1000
#endif

#ifndef EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID
#define EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID 0x314C
#endif

#define LOG_TAG "ChiakiEglRenderer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// These APIs were added in API 26 while Chiaki's minSdk is 24. Weak imports
// keep the shared library loadable on API 24/25; Kotlin also guards creation.
extern "C" media_status_t AImageReader_newWithUsage_weak(
		int32_t width, int32_t height, int32_t format, uint64_t usage,
		int32_t max_images, AImageReader **reader)
		__asm__("AImageReader_newWithUsage") __attribute__((weak));
extern "C" media_status_t AImage_getHardwareBuffer_weak(
		const AImage *image, AHardwareBuffer **buffer)
		__asm__("AImage_getHardwareBuffer") __attribute__((weak));
extern "C" jobject ANativeWindow_toSurface_weak(JNIEnv *env, ANativeWindow *window)
		__asm__("ANativeWindow_toSurface") __attribute__((weak));

namespace
{

constexpr int kMaxImages = 3;
constexpr int kStatsInterval = 120;

struct EglRenderer
{
	ANativeWindow *output_window = nullptr;
	AImageReader *image_reader = nullptr;
	ANativeWindow *decoder_window = nullptr; // owned by image_reader
	int32_t video_width = 0;
	int32_t video_height = 0;
	float sharpness = 0.0f;

	pthread_t thread{};
	pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
	pthread_cond_t condition = PTHREAD_COND_INITIALIZER;
	bool thread_started = false;
	bool stop = false;
	bool frame_pending = false;
	bool init_done = false;
	bool init_ok = false;
};

struct GlState
{
	EGLDisplay display = EGL_NO_DISPLAY;
	EGLSurface surface = EGL_NO_SURFACE;
	EGLContext context = EGL_NO_CONTEXT;
	GLuint program = 0;
	GLuint texture = 0;
	GLuint vertex_buffer = 0;
	GLint position_location = -1;
	GLint texcoord_location = -1;
	GLint texture_location = -1;
	GLint time_location = -1;
	GLint screen_size_location = -1;
	GLint sharpness_location = -1;
	bool front_buffer = false;
	PFNEGLPRESENTATIONTIMEANDROIDPROC presentation_time = nullptr;
	PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC get_native_client_buffer = nullptr;
	PFNEGLCREATEIMAGEKHRPROC create_image = nullptr;
	PFNEGLDESTROYIMAGEKHRPROC destroy_image = nullptr;
	PFNGLEGLIMAGETARGETTEXTURE2DOESPROC image_target_texture = nullptr;
};

constexpr char kVertexShader[] = R"glsl(#version 300 es
in vec2 a_Position;
in vec2 a_TexCoord;
out highp vec2 v_TexCoord;
void main() {
    gl_Position = vec4(a_Position, 0.0, 1.0);
    v_TexCoord = vec2(a_TexCoord.x, 1.0 - a_TexCoord.y);
}
)glsl";

// This is the GLSurfaceView deband/RCAS pass adapted to sample the decoder's
// imported external image directly. Avoiding its intermediate RGBA FBO is the
// reason this experimental path can plausibly beat the existing GL pipeline.
constexpr char kFragmentShader[] = R"glsl(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in highp vec2 v_TexCoord;
out vec4 outColor;
uniform samplerExternalOES u_Texture;
uniform highp float u_Time;
uniform highp vec2 u_ScreenSize;
uniform highp float u_Sharpness;
const float DEBAND_THRESHOLD = 0.02;
const int NUM_SAMPLES = 24;
const float MAX_RADIUS = 24.0;
const float GRAIN_STRENGTH = 0.003;
highp float ign(vec2 v) {
    v = floor(v * u_ScreenSize);
    return fract(52.9829189 * fract(dot(v, vec2(0.06711056, 0.00583715))));
}
highp float rand(vec2 co, float seed) {
    return fract(sin(dot(co + seed, vec2(12.9898, 78.233))) * 43758.5453);
}
void main() {
    highp vec2 texelSize = 1.0 / u_ScreenSize;
    vec3 original = texture(u_Texture, v_TexCoord).rgb;
    vec3 sum = original;
    float totalW = 1.0;
    float noise = ign(v_TexCoord);
    float timeSeed = fract(u_Time * 0.1);
    for (int i = 0; i < NUM_SAMPLES; i++) {
        float fi = float(i);
        float angle = (fi + noise) * 2.3999632;
        float r = sqrt((fi + 0.5) / float(NUM_SAMPLES)) * MAX_RADIUS;
        vec2 offset = vec2(cos(angle), sin(angle)) * r * texelSize;
        vec3 s = texture(u_Texture, clamp(v_TexCoord + offset, 0.0, 1.0)).rgb;
        float diff = max(max(abs(original.r - s.r), abs(original.g - s.g)), abs(original.b - s.b));
        float w = 1.0 - smoothstep(0.0, DEBAND_THRESHOLD, diff);
        sum += s * w;
        totalW += w;
    }
    vec3 color = sum / totalW;
    vec3 b = texture(u_Texture, v_TexCoord + vec2(0.0, -texelSize.y)).rgb;
    vec3 d = texture(u_Texture, v_TexCoord + vec2(-texelSize.x, 0.0)).rgb;
    vec3 f = texture(u_Texture, v_TexCoord + vec2(texelSize.x, 0.0)).rgb;
    vec3 h = texture(u_Texture, v_TexCoord + vec2(0.0, texelSize.y)).rgb;
    if (u_Sharpness > 0.0) {
        float peak = -1.0 / mix(8.0, 4.0, u_Sharpness);
        vec3 minRGB = min(min(min(min(b, d), f), h), color);
        vec3 maxRGB = max(max(max(max(b, d), f), h), color);
        vec3 amp = clamp((min(minRGB, 1.0 - maxRGB) - 0.01) / max(maxRGB, 0.01), 0.0, 1.0);
        amp = sqrt(amp);
        float w = peak * amp.r;
        color = clamp(((b + d + f + h) * w + color) / (4.0 * w + 1.0), 0.0, 1.0);
    }
    float dither = (ign(v_TexCoord + timeSeed) - 0.5) * 0.005;
    color += vec3(dither + GRAIN_STRENGTH * (rand(v_TexCoord, timeSeed) - 0.5));
    outColor = vec4(color, 1.0);
}
)glsl";

constexpr GLfloat kQuadVertices[] = {
	-1.0f, -1.0f, 0.0f, 0.0f,
	 1.0f, -1.0f, 1.0f, 0.0f,
	-1.0f,  1.0f, 0.0f, 1.0f,
	 1.0f,  1.0f, 1.0f, 1.0f,
};

bool has_extension(const char *extensions, const char *extension)
{
	if(!extensions || !extension || !*extension || strchr(extension, ' '))
		return false;
	const size_t length = strlen(extension);
	const char *match = extensions;
	while((match = strstr(match, extension)))
	{
		const bool starts_word = match == extensions || match[-1] == ' ';
		const bool ends_word = match[length] == '\0' || match[length] == ' ';
		if(starts_word && ends_word)
			return true;
		match += length;
	}
	return false;
}

int64_t monotonic_ns()
{
	timespec now{};
	clock_gettime(CLOCK_MONOTONIC, &now);
	return static_cast<int64_t>(now.tv_sec) * 1000000000LL + now.tv_nsec;
}

GLuint compile_shader(GLenum type, const char *source)
{
	GLuint shader = glCreateShader(type);
	glShaderSource(shader, 1, &source, nullptr);
	glCompileShader(shader);
	GLint compiled = GL_FALSE;
	glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
	if(compiled == GL_TRUE)
		return shader;
	GLchar log[1024]{};
	glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
	LOGE("Shader compilation failed: %s", log);
	glDeleteShader(shader);
	return 0;
}

bool create_program(GlState &gl)
{
	GLuint vertex = compile_shader(GL_VERTEX_SHADER, kVertexShader);
	GLuint fragment = compile_shader(GL_FRAGMENT_SHADER, kFragmentShader);
	if(!vertex || !fragment)
	{
		if(vertex)
			glDeleteShader(vertex);
		if(fragment)
			glDeleteShader(fragment);
		return false;
	}
	gl.program = glCreateProgram();
	glAttachShader(gl.program, vertex);
	glAttachShader(gl.program, fragment);
	glLinkProgram(gl.program);
	glDeleteShader(vertex);
	glDeleteShader(fragment);
	GLint linked = GL_FALSE;
	glGetProgramiv(gl.program, GL_LINK_STATUS, &linked);
	if(linked != GL_TRUE)
	{
		GLchar log[1024]{};
		glGetProgramInfoLog(gl.program, sizeof(log), nullptr, log);
		LOGE("Program link failed: %s", log);
		return false;
	}
	gl.position_location = glGetAttribLocation(gl.program, "a_Position");
	gl.texcoord_location = glGetAttribLocation(gl.program, "a_TexCoord");
	gl.texture_location = glGetUniformLocation(gl.program, "u_Texture");
	gl.time_location = glGetUniformLocation(gl.program, "u_Time");
	gl.screen_size_location = glGetUniformLocation(gl.program, "u_ScreenSize");
	gl.sharpness_location = glGetUniformLocation(gl.program, "u_Sharpness");
	glGenBuffers(1, &gl.vertex_buffer);
	glBindBuffer(GL_ARRAY_BUFFER, gl.vertex_buffer);
	glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVertices), kQuadVertices, GL_STATIC_DRAW);
	glGenTextures(1, &gl.texture);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, gl.texture);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	return glGetError() == GL_NO_ERROR;
}

bool choose_config(EGLDisplay display, bool mutable_buffer, EGLConfig *config)
{
	const EGLint surface_bits = EGL_WINDOW_BIT |
			(mutable_buffer ? EGL_MUTABLE_RENDER_BUFFER_BIT_KHR : 0);
	const EGLint attributes[] = {
		EGL_SURFACE_TYPE, surface_bits,
		EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
		EGL_RED_SIZE, 8,
		EGL_GREEN_SIZE, 8,
		EGL_BLUE_SIZE, 8,
		EGL_ALPHA_SIZE, 8,
		EGL_NONE,
	};
	EGLint count = 0;
	return eglChooseConfig(display, attributes, config, 1, &count) == EGL_TRUE && count == 1;
}

bool initialize_gl(EglRenderer *renderer, GlState &gl)
{
	gl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if(gl.display == EGL_NO_DISPLAY || eglInitialize(gl.display, nullptr, nullptr) != EGL_TRUE)
	{
		LOGE("eglInitialize failed: %#x", eglGetError());
		return false;
	}
	const char *egl_extensions = eglQueryString(gl.display, EGL_EXTENSIONS);
	const bool mutable_supported = has_extension(egl_extensions, "EGL_KHR_mutable_render_buffer");
	EGLConfig config = nullptr;
	bool mutable_config = mutable_supported && choose_config(gl.display, true, &config);
	if(!mutable_config && !choose_config(gl.display, false, &config))
	{
		LOGE("No EGL window config for GLES 3: %#x", eglGetError());
		return false;
	}
	EGLint native_format = 0;
	eglGetConfigAttrib(gl.display, config, EGL_NATIVE_VISUAL_ID, &native_format);
	ANativeWindow_setBuffersGeometry(renderer->output_window, 0, 0, native_format);
	const EGLint context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
	gl.context = eglCreateContext(gl.display, config, EGL_NO_CONTEXT, context_attributes);
	gl.surface = eglCreateWindowSurface(gl.display, config, renderer->output_window, nullptr);
	if(gl.context == EGL_NO_CONTEXT || gl.surface == EGL_NO_SURFACE ||
			eglMakeCurrent(gl.display, gl.surface, gl.surface, gl.context) != EGL_TRUE)
	{
		LOGE("Failed to create/make-current EGL context: %#x", eglGetError());
		return false;
	}
	gl.get_native_client_buffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
			eglGetProcAddress("eglGetNativeClientBufferANDROID"));
	gl.create_image = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
	gl.destroy_image = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
	gl.image_target_texture = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
			eglGetProcAddress("glEGLImageTargetTexture2DOES"));
	if(!gl.get_native_client_buffer || !gl.create_image || !gl.destroy_image || !gl.image_target_texture)
	{
		LOGE("Required AHardwareBuffer EGL image extensions are unavailable");
		return false;
	}
	if(has_extension(egl_extensions, "EGL_ANDROID_presentation_time"))
		gl.presentation_time = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
				eglGetProcAddress("eglPresentationTimeANDROID"));
	if(!gl.presentation_time)
		LOGW("EGL_ANDROID_presentation_time unavailable");

	if(mutable_config && eglSurfaceAttrib(gl.display, gl.surface, EGL_RENDER_BUFFER, EGL_SINGLE_BUFFER) == EGL_TRUE)
	{
		// KHR_mutable_render_buffer applies the pending mode change on this swap.
		if(eglSwapBuffers(gl.display, gl.surface) == EGL_TRUE)
		{
			EGLint render_buffer = EGL_BACK_BUFFER;
			eglQuerySurface(gl.display, gl.surface, EGL_RENDER_BUFFER, &render_buffer);
			gl.front_buffer = render_buffer == EGL_SINGLE_BUFFER;
		}
	}
	if(gl.front_buffer)
	{
		if(has_extension(egl_extensions, "EGL_ANDROID_front_buffer_auto_refresh") &&
				eglSurfaceAttrib(gl.display, gl.surface, EGL_FRONT_BUFFER_AUTO_REFRESH_ANDROID, EGL_TRUE) != EGL_TRUE)
			LOGW("Front-buffer auto-refresh request failed: %#x", eglGetError());
		LOGI("Enabled front-buffer rendering mode");
	}
	else
	{
		LOGW("EGL front-buffer mode unavailable; using double-buffered EGL");
	}
	if(!create_program(gl))
		return false;
	glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
	LOGI("Initialized AImageReader EGL renderer (%dx%d, %s)", renderer->video_width,
			renderer->video_height, gl.front_buffer ? "front buffer" : "double buffer");
	return true;
}

void destroy_gl(GlState &gl)
{
	if(gl.display == EGL_NO_DISPLAY)
		return;
	if(gl.context != EGL_NO_CONTEXT && gl.surface != EGL_NO_SURFACE)
		eglMakeCurrent(gl.display, gl.surface, gl.surface, gl.context);
	if(gl.texture)
		glDeleteTextures(1, &gl.texture);
	if(gl.vertex_buffer)
		glDeleteBuffers(1, &gl.vertex_buffer);
	if(gl.program)
		glDeleteProgram(gl.program);
	eglMakeCurrent(gl.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
	if(gl.surface != EGL_NO_SURFACE)
		eglDestroySurface(gl.display, gl.surface);
	if(gl.context != EGL_NO_CONTEXT)
		eglDestroyContext(gl.display, gl.context);
	eglTerminate(gl.display);
}

bool render_image(EglRenderer *renderer, GlState &gl, AImage *image, float frame_count)
{
	AHardwareBuffer *hardware_buffer = nullptr;
	if(AImage_getHardwareBuffer_weak(image, &hardware_buffer) != AMEDIA_OK || !hardware_buffer)
	{
		LOGW("AImage_getHardwareBuffer failed");
		return false;
	}
	EGLClientBuffer client_buffer = gl.get_native_client_buffer(hardware_buffer);
	if(!client_buffer)
	{
		LOGW("eglGetNativeClientBufferANDROID failed");
		return false;
	}
	const EGLint attributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
	EGLImageKHR egl_image = gl.create_image(gl.display, EGL_NO_CONTEXT,
			EGL_NATIVE_BUFFER_ANDROID, client_buffer, attributes);
	if(egl_image == EGL_NO_IMAGE_KHR)
	{
		LOGW("eglCreateImageKHR failed: %#x", eglGetError());
		return false;
	}

	const int output_width = ANativeWindow_getWidth(renderer->output_window);
	const int output_height = ANativeWindow_getHeight(renderer->output_window);
	glViewport(0, 0, output_width, output_height);
	glClear(GL_COLOR_BUFFER_BIT);
	glUseProgram(gl.program);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_EXTERNAL_OES, gl.texture);
	gl.image_target_texture(GL_TEXTURE_EXTERNAL_OES, egl_image);
	glUniform1i(gl.texture_location, 0);
	glUniform1f(gl.time_location, frame_count);
	glUniform2f(gl.screen_size_location, static_cast<float>(renderer->video_width),
			static_cast<float>(renderer->video_height));
	glUniform1f(gl.sharpness_location, renderer->sharpness);
	glBindBuffer(GL_ARRAY_BUFFER, gl.vertex_buffer);
	glEnableVertexAttribArray(static_cast<GLuint>(gl.position_location));
	glVertexAttribPointer(static_cast<GLuint>(gl.position_location), 2, GL_FLOAT, GL_FALSE,
			4 * sizeof(GLfloat), reinterpret_cast<void *>(0));
	glEnableVertexAttribArray(static_cast<GLuint>(gl.texcoord_location));
	glVertexAttribPointer(static_cast<GLuint>(gl.texcoord_location), 2, GL_FLOAT, GL_FALSE,
			4 * sizeof(GLfloat), reinterpret_cast<void *>(2 * sizeof(GLfloat)));
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

	const int64_t presentation_ns = monotonic_ns();
	if(gl.presentation_time)
		gl.presentation_time(gl.display, gl.surface, presentation_ns);
	bool submitted = true;
	if(gl.front_buffer)
		glFlush();
	else
		submitted = eglSwapBuffers(gl.display, gl.surface) == EGL_TRUE;
	if(glGetError() != GL_NO_ERROR)
		submitted = false;
	gl.destroy_image(gl.display, egl_image);
	return submitted;
}

void image_available(void *context, AImageReader *)
{
	auto *renderer = static_cast<EglRenderer *>(context);
	pthread_mutex_lock(&renderer->mutex);
	if(!renderer->stop)
	{
		renderer->frame_pending = true;
		pthread_cond_signal(&renderer->condition);
	}
	pthread_mutex_unlock(&renderer->mutex);
}

void *render_thread(void *arg)
{
	auto *renderer = static_cast<EglRenderer *>(arg);
	GlState gl;
	const bool initialized = initialize_gl(renderer, gl);
	pthread_mutex_lock(&renderer->mutex);
	renderer->init_ok = initialized;
	renderer->init_done = true;
	pthread_cond_broadcast(&renderer->condition);
	pthread_mutex_unlock(&renderer->mutex);
	if(!initialized)
	{
		destroy_gl(gl);
		return nullptr;
	}

	uint64_t frame_number = 0;
	int64_t accumulated_render_ns = 0;
	while(true)
	{
		pthread_mutex_lock(&renderer->mutex);
		while(!renderer->stop && !renderer->frame_pending)
			pthread_cond_wait(&renderer->condition, &renderer->mutex);
		if(renderer->stop)
		{
			pthread_mutex_unlock(&renderer->mutex);
			break;
		}
		renderer->frame_pending = false;
		pthread_mutex_unlock(&renderer->mutex);

		AImage *image = nullptr;
		const media_status_t status = AImageReader_acquireLatestImage(renderer->image_reader, &image);
		if(status != AMEDIA_OK || !image)
			continue;
		ATrace_beginSection("ChiakiEglRenderFrame");
		const int64_t start_ns = monotonic_ns();
		if(!render_image(renderer, gl, image, static_cast<float>(frame_number % 1000000)))
			LOGW("Failed to submit EGL frame %llu", static_cast<unsigned long long>(frame_number));
		accumulated_render_ns += monotonic_ns() - start_ns;
		AImage_delete(image);
		ATrace_endSection();
		frame_number++;
		if(frame_number % kStatsInterval == 0)
		{
			LOGI("Rendered %llu frames; mean acquire-to-submit %.3f ms; mode=%s",
					static_cast<unsigned long long>(frame_number),
					static_cast<double>(accumulated_render_ns) / kStatsInterval / 1000000.0,
					gl.front_buffer ? "front" : "double");
			accumulated_render_ns = 0;
		}
	}
	destroy_gl(gl);
	return nullptr;
}

void destroy_renderer(EglRenderer *renderer)
{
	if(!renderer)
		return;
	if(renderer->image_reader)
		AImageReader_setImageListener(renderer->image_reader, nullptr);
	pthread_mutex_lock(&renderer->mutex);
	renderer->stop = true;
	pthread_cond_broadcast(&renderer->condition);
	pthread_mutex_unlock(&renderer->mutex);
	if(renderer->thread_started)
		pthread_join(renderer->thread, nullptr);
	if(renderer->image_reader)
		AImageReader_delete(renderer->image_reader);
	if(renderer->output_window)
		ANativeWindow_release(renderer->output_window);
	pthread_cond_destroy(&renderer->condition);
	pthread_mutex_destroy(&renderer->mutex);
	delete renderer;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_fi_madekivi_pleikkari_stream_EglRenderer_nativeCreate(
		JNIEnv *env, jobject, jobject output_surface, jint width, jint height, jfloat sharpness)
{
	if(!output_surface || width <= 0 || height <= 0 ||
			!AImageReader_newWithUsage_weak || !AImage_getHardwareBuffer_weak ||
			!ANativeWindow_toSurface_weak)
	{
		LOGE("EGL renderer requires Android API 26 and a valid output surface");
		return 0;
	}
	auto *renderer = new EglRenderer();
	renderer->output_window = ANativeWindow_fromSurface(env, output_surface);
	renderer->video_width = width;
	renderer->video_height = height;
	renderer->sharpness = sharpness;
	if(!renderer->output_window)
	{
		destroy_renderer(renderer);
		return 0;
	}
	media_status_t status = AImageReader_newWithUsage_weak(width, height, AIMAGE_FORMAT_PRIVATE,
			AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, kMaxImages, &renderer->image_reader);
	if(status != AMEDIA_OK)
	{
		LOGE("AImageReader_newWithUsage failed: %d", status);
		destroy_renderer(renderer);
		return 0;
	}
	status = AImageReader_getWindow(renderer->image_reader, &renderer->decoder_window);
	if(status != AMEDIA_OK || !renderer->decoder_window)
	{
		LOGE("AImageReader_getWindow failed: %d", status);
		destroy_renderer(renderer);
		return 0;
	}
	AImageReader_ImageListener listener{ renderer, image_available };
	status = AImageReader_setImageListener(renderer->image_reader, &listener);
	if(status != AMEDIA_OK)
	{
		LOGE("AImageReader_setImageListener failed: %d", status);
		destroy_renderer(renderer);
		return 0;
	}
	if(pthread_create(&renderer->thread, nullptr, render_thread, renderer) != 0)
	{
		LOGE("Failed to start EGL render thread");
		destroy_renderer(renderer);
		return 0;
	}
	renderer->thread_started = true;
	pthread_mutex_lock(&renderer->mutex);
	while(!renderer->init_done)
		pthread_cond_wait(&renderer->condition, &renderer->mutex);
	const bool init_ok = renderer->init_ok;
	pthread_mutex_unlock(&renderer->mutex);
	if(!init_ok)
	{
		destroy_renderer(renderer);
		return 0;
	}
	return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT jobject JNICALL
Java_fi_madekivi_pleikkari_stream_EglRenderer_nativeGetDecoderSurface(
		JNIEnv *env, jobject, jlong handle)
{
	auto *renderer = reinterpret_cast<EglRenderer *>(handle);
	if(!renderer || !renderer->decoder_window || !ANativeWindow_toSurface_weak)
		return nullptr;
	return ANativeWindow_toSurface_weak(env, renderer->decoder_window);
}

extern "C" JNIEXPORT void JNICALL
Java_fi_madekivi_pleikkari_stream_EglRenderer_nativeDestroy(JNIEnv *, jobject, jlong handle)
{
	destroy_renderer(reinterpret_cast<EglRenderer *>(handle));
}
