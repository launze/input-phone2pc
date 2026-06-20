#pragma once

#include <cstddef>
#include <memory>
#include <optional>
#include <string>
#include <vector>

struct EncodedFrame {
    int width = 0;
    int height = 0;
    std::vector<unsigned char> rgb;
};

class FileQrEncoder {
public:
    bool LoadData(std::string raw, std::string filenameUtf8, int mode, std::string* error);
    std::optional<EncodedFrame> NextFrame();

    bool HasFile() const;
    int FrameCount() const;
    const std::string& FileName() const;
    size_t FileSize() const;
    int Mode() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;

public:
    FileQrEncoder();
    ~FileQrEncoder();
    FileQrEncoder(const FileQrEncoder&) = delete;
    FileQrEncoder& operator=(const FileQrEncoder&) = delete;
};
