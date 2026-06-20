#include "qr_encoder_backend.h"

#include "cimb_translator/Config.h"
#include "compression/zstd_compressor.h"
#include "encoder/Encoder.h"
#include "fountain/FountainInit.h"
#include "fountain/fountain_encoder_stream.h"

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <utility>

namespace {

constexpr int kDefaultMode = 68;
constexpr int kCompressionLevel = 16;
constexpr int kInitialEncodeId = 109;

void SetError(std::string* target, const char* value)
{
    if (target) {
        *target = value;
    }
}

EncodedFrame ToEncodedFrame(const cv::Mat& src)
{
    cv::Mat rgb;
    if (src.channels() == 4) {
        cv::cvtColor(src, rgb, cv::COLOR_BGRA2RGB);
    } else if (src.channels() == 3) {
        cv::cvtColor(src, rgb, cv::COLOR_BGR2RGB);
    } else {
        cv::cvtColor(src, rgb, cv::COLOR_GRAY2RGB);
    }

    if (!rgb.isContinuous()) {
        rgb = rgb.clone();
    }

    EncodedFrame result;
    result.width = rgb.cols;
    result.height = rgb.rows;
    const size_t bytes = static_cast<size_t>(rgb.cols) * static_cast<size_t>(rgb.rows) * 3;
    result.rgb.resize(bytes);
    std::copy(rgb.data, rgb.data + bytes, result.rgb.data());
    return result;
}

} // namespace

class FileQrEncoder::Impl {
public:
    bool LoadData(std::string raw, std::string filenameUtf8, int mode, std::string* error)
    {
        cimbar::Config::update(mode);
        if (!FountainInit::init()) {
            SetError(error, "初始化 fountain 编码器失败");
            return false;
        }

        cimbar::zstd_compressor<std::stringstream> compressed;
        compressed.set_compression_level(kCompressionLevel);

        if (!filenameUtf8.empty()) {
            compressed.write_header(filenameUtf8.data(), static_cast<unsigned>(filenameUtf8.size()));
        }

        if (!compressed.write(raw.data(), raw.size())) {
            SetError(error, "文件压缩失败");
            return false;
        }

        const unsigned chunkSize = cimbar::Config::fountain_chunk_size();
        const size_t compressedSize = compressed.size();
        if (compressedSize < chunkSize) {
            compressed.pad(static_cast<unsigned>(chunkSize - compressedSize + 1));
        }

        ++encodeId_;
        stream_ = fountain_encoder_stream::create(compressed, chunkSize, encodeId_);
        frameCount_ = 0;
        if (!stream_) {
            SetError(error, "创建二维码帧流失败");
            return false;
        }

        fileName_ = std::move(filenameUtf8);
        fileSize_ = raw.size();
        mode_ = mode;
        return true;
    }

    std::optional<EncodedFrame> NextFrame()
    {
        if (!stream_) {
            return std::nullopt;
        }

        const unsigned required = stream_->blocks_required() * 8;
        if (stream_->block_count() > required) {
            stream_->restart();
            frameCount_ = 0;
        }

        Encoder encoder;
        encoder.set_encode_id(encodeId_);
        auto frame = encoder.encode_next(*stream_);
        if (!frame) {
            return std::nullopt;
        }

        ++frameCount_;
        return ToEncodedFrame(*frame);
    }

    bool HasFile() const { return static_cast<bool>(stream_); }
    int FrameCount() const { return frameCount_; }
    const std::string& FileName() const { return fileName_; }
    size_t FileSize() const { return fileSize_; }
    int Mode() const { return mode_; }

private:
    fountain_encoder_stream::ptr stream_;
    uint8_t encodeId_ = kInitialEncodeId;
    int frameCount_ = 0;
    std::string fileName_;
    size_t fileSize_ = 0;
    int mode_ = kDefaultMode;
};

FileQrEncoder::FileQrEncoder()
    : impl_(std::make_unique<Impl>())
{
}

FileQrEncoder::~FileQrEncoder() = default;

bool FileQrEncoder::LoadData(std::string raw, std::string filenameUtf8, int mode, std::string* error)
{
    return impl_->LoadData(std::move(raw), std::move(filenameUtf8), mode, error);
}

std::optional<EncodedFrame> FileQrEncoder::NextFrame()
{
    return impl_->NextFrame();
}

bool FileQrEncoder::HasFile() const
{
    return impl_->HasFile();
}

int FileQrEncoder::FrameCount() const
{
    return impl_->FrameCount();
}

const std::string& FileQrEncoder::FileName() const
{
    return impl_->FileName();
}

size_t FileQrEncoder::FileSize() const
{
    return impl_->FileSize();
}

int FileQrEncoder::Mode() const
{
    return impl_->Mode();
}
