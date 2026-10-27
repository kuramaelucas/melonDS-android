#include "OboeCallback.h"
#include "types.h"
#include "Platform.h"
#include "SPU.h"

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

using namespace melonDS;

#define INTERNAL_FRAME_RATE 59.8260982880808f

OboeCallback::OboeCallback(int volume, void (*onErrorCallback)(void), std::ostream* recordingStream) : _volume(volume), onErrorCallback(onErrorCallback), _recordingStream(recordingStream) {
    audioSampleFrac = 0;
}

oboe::DataCallbackResult
OboeCallback::onAudioReady(oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    auto currentInstance = activeInstance.lock();

    if (!currentInstance)
    {
        memset(audioData, 0, numFrames * sizeof(u16) * 2);
        return oboe::DataCallbackResult::Continue;
    }

    int len = numFrames;

    double skew = std::clamp(60.0 / INTERNAL_FRAME_RATE, 0.995, 1.005);
    currentInstance->setAudioOutputSkew(skew);

    int len_in = getNumSamplesOut(len);
    if (len_in > numFrames) len_in = numFrames;

    int num_in = currentInstance->readAudioOutput((s16*) audioData, len_in);

    if (num_in < 1)
    {
        memset(audioData, 0, len * sizeof(s16) * 2);
        return oboe::DataCallbackResult::Continue;
    }

    if (_volume < 256)
    {
        s16* samples = (s16*) audioData;
        int totalSamples = num_in * 2;
        int i = 0;

#if defined(__ARM_NEON) || defined(__aarch64__)
        int16x8_t vol_vec = vdupq_n_s16((s16)_volume);
        for (; i <= totalSamples - 8; i += 8)
        {
            int16x8_t smp = vld1q_s16(&samples[i]);
            int32x4_t low = vmull_s16(vget_low_s16(smp), vget_low_s16(vol_vec));
            int32x4_t high = vmull_s16(vget_high_s16(smp), vget_high_s16(vol_vec));
            int16x4_t res_low = vshrn_n_s32(low, 8);
            int16x4_t res_high = vshrn_n_s32(high, 8);
            vst1q_s16(&samples[i], vcombine_s16(res_low, res_high));
        }
#endif

        for (; i < totalSamples; i++)
            samples[i] = ((s32) samples[i] * _volume) >> 8;
    }

    if (num_in < len_in)
    {
        int last = num_in - 1;

        for (int i = num_in; i < len_in; i++)
            ((u32*)audioData)[i] = ((u32*)audioData)[last];
    }

    if (_recordingStream) [[unlikely]]
        _recordingStream->write((char*) audioData, numFrames * sizeof(s16) * 2);

    return oboe::DataCallbackResult::Continue;
}

void OboeCallback::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result result)
{
    if (result == oboe::Result::ErrorDisconnected && onErrorCallback != nullptr) {
        onErrorCallback();
    }
}

int OboeCallback::getNumSamplesOut(int len)
{
    // TODO: adjust to game speed
    float f_len_in = len /* * (curFPS/60.0)*/;
    f_len_in += audioSampleFrac;
    int len_in = (int) floor(f_len_in);
    audioSampleFrac = f_len_in - len_in;

    return len_in;
}