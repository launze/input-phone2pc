/*
 * Minimal native GUI for VoiceInput file QR generation.
 *
 * The encoding path intentionally mirrors libcimbar's web sender:
 * Config mode B, zstd compression, fountain stream, Encoder::encode_next().
 * Rendering uses wxWidgets paint events and CPU bitmaps, so it does not need
 * WebGL, WebView, OpenGL, or browser GPU acceleration.
 */

#include "cimb_translator/Config.h"
#include "compression/zstd_compressor.h"
#include "encoder/Encoder.h"
#include "fountain/FountainInit.h"
#include "fountain/fountain_encoder_stream.h"
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

// libcorrect defines ssize_t on MSVC; tell wxWidgets not to define it again.
#if defined(_MSC_VER) && !defined(HAVE_SSIZE_T)
#define HAVE_SSIZE_T
#endif

#include <wx/wx.h>
#include <wx/dcbuffer.h>
#include <wx/file.h>
#include <wx/filedlg.h>
#include <wx/filename.h>
#include <wx/choice.h>
#include <wx/tglbtn.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <iterator>
#include <memory>
#include <optional>
#include <sstream>
#include <string>

namespace {

constexpr int kDefaultMode = 68;
constexpr int kCompressionLevel = 16;
constexpr int kTargetFps = 45;
constexpr int kInitialEncodeId = 109;
constexpr int kFpsOptions[] = {15, 20, 25, 30, 40, 45, 50, 60};

wxString U8(const char* value)
{
    return wxString::FromUTF8(value);
}

struct ModeOption {
    const char* label;
    int value;
};

const ModeOption kModeOptions[] = {
    {"C2", 68},
    {"Bm", 67},
    {"Bu", 66},
    {"4C", 4},
    {"8C", 8},
};

std::string ToUtf8(const wxString& value)
{
    wxCharBuffer buffer = value.utf8_str();
    return std::string(buffer.data(), buffer.length());
}

std::string BasenameUtf8(const wxString& path)
{
    return ToUtf8(wxFileName(path).GetFullName());
}

bool ReadFileBytes(const wxString& path, std::string* out)
{
    wxFile file(path, wxFile::read);
    if (!file.IsOpened()) {
        return false;
    }

    const wxFileOffset length = file.Length();
    if (length < 0) {
        return false;
    }

    out->assign(static_cast<size_t>(length), '\0');
    if (length == 0) {
        return true;
    }

    const auto bytesRead = file.Read(&(*out)[0], static_cast<size_t>(length));
    return bytesRead == static_cast<decltype(bytesRead)>(length);
}

wxBitmap MatToBitmap(const cv::Mat& src)
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

    wxImage image(rgb.cols, rgb.rows);
    unsigned char* dst = image.GetData();
    const size_t bytes = static_cast<size_t>(rgb.cols) * static_cast<size_t>(rgb.rows) * 3;
    std::copy(rgb.data, rgb.data + bytes, dst);
    return wxBitmap(image);
}

class FileQrEncoder {
public:
    bool LoadFile(const wxString& path, int mode, wxString* error)
    {
        cimbar::Config::update(mode);
        if (!FountainInit::init()) {
            SetError(error, U8("初始化 fountain 编码器失败"));
            return false;
        }

        std::string raw;
        if (!ReadFileBytes(path, &raw)) {
            SetError(error, U8("无法读取文件"));
            return false;
        }

        cimbar::zstd_compressor<std::stringstream> compressed;
        compressed.set_compression_level(kCompressionLevel);

        const std::string filename = BasenameUtf8(path);
        if (!filename.empty()) {
            compressed.write_header(filename.data(), static_cast<unsigned>(filename.size()));
        }

        if (!compressed.write(raw.data(), raw.size())) {
            SetError(error, U8("文件压缩失败"));
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
            SetError(error, U8("创建二维码帧流失败"));
            return false;
        }

        fileName_ = wxFileName(path).GetFullName();
        filePath_ = path;
        fileSize_ = raw.size();
        mode_ = mode;
        return true;
    }

    std::optional<wxBitmap> NextBitmap()
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
        return MatToBitmap(*frame);
    }

    bool HasFile() const { return static_cast<bool>(stream_); }
    int FrameCount() const { return frameCount_; }
    wxString FileName() const { return fileName_; }
    wxString FilePath() const { return filePath_; }
    size_t FileSize() const { return fileSize_; }
    int Mode() const { return mode_; }

private:
    static void SetError(wxString* target, const wxString& value)
    {
        if (target) {
            *target = value;
        }
    }

    fountain_encoder_stream::ptr stream_;
    uint8_t encodeId_ = kInitialEncodeId;
    int frameCount_ = 0;
    wxString fileName_;
    wxString filePath_;
    size_t fileSize_ = 0;
    int mode_ = kDefaultMode;
};

class QrCanvas final : public wxPanel {
public:
    explicit QrCanvas(wxWindow* parent)
        : wxPanel(parent, wxID_ANY, wxDefaultPosition, wxDefaultSize, wxBORDER_SIMPLE)
    {
        SetBackgroundStyle(wxBG_STYLE_PAINT);
        Bind(wxEVT_PAINT, &QrCanvas::OnPaint, this);
        Bind(wxEVT_SIZE, [this](wxSizeEvent& event) {
            Refresh(false);
            event.Skip();
        });
    }

    void SetFrame(const wxBitmap& bitmap)
    {
        bitmap_ = bitmap;
        Refresh(false);
    }

    void Clear()
    {
        bitmap_ = wxBitmap();
        Refresh(false);
    }

private:
    void OnPaint(wxPaintEvent&)
    {
        wxAutoBufferedPaintDC dc(this);
        const wxSize size = GetClientSize();
        dc.SetBackground(wxBrush(wxColour(18, 18, 18)));
        dc.Clear();

        if (!bitmap_.IsOk()) {
            dc.SetTextForeground(wxColour(210, 210, 210));
            const wxString text = U8("请选择文件");
            const wxSize extent = dc.GetTextExtent(text);
            dc.DrawText(text, (size.x - extent.x) / 2, (size.y - extent.y) / 2);
            return;
        }

        const int sourceW = bitmap_.GetWidth();
        const int sourceH = bitmap_.GetHeight();
        if (sourceW <= 0 || sourceH <= 0 || size.x <= 0 || size.y <= 0) {
            return;
        }

        const double scale = std::min(
            static_cast<double>(size.x) / static_cast<double>(sourceW),
            static_cast<double>(size.y) / static_cast<double>(sourceH)
        );
        const int targetW = std::max(1, static_cast<int>(sourceW * scale));
        const int targetH = std::max(1, static_cast<int>(sourceH * scale));
        const int x = (size.x - targetW) / 2;
        const int y = (size.y - targetH) / 2;

        wxImage image = bitmap_.ConvertToImage();
        image.Rescale(targetW, targetH, wxIMAGE_QUALITY_NEAREST);
        dc.DrawBitmap(wxBitmap(image), x, y, false);
    }

    wxBitmap bitmap_;
};

class MainFrame final : public wxFrame {
public:
    MainFrame()
        : wxFrame(nullptr, wxID_ANY, U8("语传文件二维码生成工具"), wxDefaultPosition, wxSize(1080, 760))
        , timer_(this)
    {
        SetFont(wxFontInfo(10).FaceName(U8("Microsoft YaHei UI")));

        auto* root = new wxBoxSizer(wxHORIZONTAL);
        auto* side = new wxPanel(this, wxID_ANY, wxDefaultPosition, wxSize(280, -1));
        auto* sideSizer = new wxBoxSizer(wxVERTICAL);

        chooseButton_ = new wxButton(side, wxID_ANY, U8("打开文件"));
        sideSizer->Add(chooseButton_, 0, wxEXPAND | wxALL, 10);

        sideSizer->Add(new wxStaticText(side, wxID_ANY, U8("模式")), 0, wxLEFT | wxRIGHT | wxTOP, 10);
        modeChoice_ = new wxChoice(side, wxID_ANY);
        for (const auto& option : kModeOptions) {
            modeChoice_->Append(U8(option.label));
        }
        modeChoice_->SetSelection(0);
        sideSizer->Add(modeChoice_, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, 10);

        sideSizer->Add(new wxStaticText(side, wxID_ANY, U8("帧率")), 0, wxLEFT | wxRIGHT, 10);
        auto* fpsSizer = new wxGridSizer(4, 6, 6);
        for (size_t i = 0; i < std::size(kFpsOptions); ++i) {
            auto* button = new wxToggleButton(side, wxID_ANY, wxString::Format(U8("%d"), kFpsOptions[i]));
            button->SetValue(kFpsOptions[i] == kTargetFps);
            fpsButtons_[i] = button;
            fpsSizer->Add(button, 1, wxEXPAND);
            Bind(wxEVT_TOGGLEBUTTON, &MainFrame::OnFpsSelected, this, button->GetId());
        }
        sideSizer->Add(fpsSizer, 0, wxEXPAND | wxLEFT | wxRIGHT | wxBOTTOM, 10);

        fileNameText_ = new wxStaticText(side, wxID_ANY, U8("当前文件：未选择"));
        fileNameText_->Wrap(250);
        fileSizeText_ = new wxStaticText(side, wxID_ANY, U8("文件大小：-"));
        frameText_ = new wxStaticText(side, wxID_ANY, U8("当前帧：0"));
        statusText_ = new wxStaticText(side, wxID_ANY, U8("状态：等待选择文件"));
        statusText_->Wrap(250);

        sideSizer->Add(fileNameText_, 0, wxEXPAND | wxLEFT | wxRIGHT | wxTOP, 10);
        sideSizer->Add(fileSizeText_, 0, wxEXPAND | wxLEFT | wxRIGHT | wxTOP, 10);
        sideSizer->Add(frameText_, 0, wxEXPAND | wxLEFT | wxRIGHT | wxTOP, 10);
        sideSizer->Add(statusText_, 0, wxEXPAND | wxLEFT | wxRIGHT | wxTOP, 10);
        sideSizer->AddStretchSpacer(1);

        side->SetSizer(sideSizer);

        canvas_ = new QrCanvas(this);
        root->Add(side, 0, wxEXPAND);
        root->Add(canvas_, 1, wxEXPAND | wxALL, 8);
        SetSizer(root);

        Bind(wxEVT_BUTTON, &MainFrame::OnChooseFile, this, chooseButton_->GetId());
        Bind(wxEVT_TIMER, &MainFrame::OnTimer, this);
    }

private:
    int SelectedMode() const
    {
        const int selection = modeChoice_ ? modeChoice_->GetSelection() : 0;
        if (selection < 0 || selection >= static_cast<int>(std::size(kModeOptions))) {
            return kDefaultMode;
        }
        return kModeOptions[selection].value;
    }

    int SelectedFps() const
    {
        return selectedFps_;
    }

    void RestartTimer()
    {
        if (encoder_.HasFile()) {
            timer_.Start(std::max(1, 1000 / SelectedFps()));
        }
    }

    void OnFpsSelected(wxCommandEvent& event)
    {
        for (size_t i = 0; i < fpsButtons_.size(); ++i) {
            if (fpsButtons_[i] && fpsButtons_[i]->GetId() == event.GetId()) {
                selectedFps_ = kFpsOptions[i];
            }
        }
        SyncFpsButtons();
        RestartTimer();
        UpdateStatus();
    }

    void SyncFpsButtons()
    {
        for (size_t i = 0; i < fpsButtons_.size(); ++i) {
            if (fpsButtons_[i]) {
                fpsButtons_[i]->SetValue(kFpsOptions[i] == selectedFps_);
            }
        }
    }

    void OnChooseFile(wxCommandEvent&)
    {
        wxFileDialog dialog(
            this,
            U8("选择要生成动态二维码的文件"),
            wxEmptyString,
            wxEmptyString,
            U8("所有文件 (*.*)|*.*"),
            wxFD_OPEN | wxFD_FILE_MUST_EXIST
        );

        if (dialog.ShowModal() != wxID_OK) {
            return;
        }

        wxString error;
        timer_.Stop();
        canvas_->Clear();
        statusText_->SetLabel(U8("状态：正在编码..."));

        if (!encoder_.LoadFile(dialog.GetPath(), SelectedMode(), &error)) {
            statusText_->SetLabel(U8("状态：编码失败"));
            wxMessageBox(error, U8("错误"), wxOK | wxICON_ERROR, this);
            return;
        }

        SetTitle(U8("语传文件二维码生成工具 - ") + encoder_.FileName());
        fileNameText_->SetLabel(U8("当前文件：") + encoder_.FileName());
        fileNameText_->Wrap(250);
        fileSizeText_->SetLabel(U8("文件大小：") + FormatBytes(encoder_.FileSize()));
        UpdateStatus();
        RestartTimer();
    }

    void OnTimer(wxTimerEvent&)
    {
        if (!encoder_.HasFile()) {
            return;
        }

        auto bitmap = encoder_.NextBitmap();
        if (!bitmap) {
            statusText_->SetLabel(U8("状态：生成帧失败"));
            return;
        }

        canvas_->SetFrame(*bitmap);
        if (encoder_.FrameCount() % SelectedFps() == 0) {
            UpdateStatus();
        }
    }

    void UpdateStatus()
    {
        frameText_->SetLabel(wxString::Format(U8("当前帧：%d"), encoder_.FrameCount()));
        statusText_->SetLabel(wxString::Format(U8("状态：显示中，%d fps，模式 %s"),
            SelectedFps(),
            modeChoice_->GetStringSelection()));
        statusText_->Wrap(250);
    }

    wxString FormatBytes(size_t bytes) const
    {
        const double value = static_cast<double>(bytes);
        if (bytes >= 1024ull * 1024ull * 1024ull) {
            return wxString::Format(U8("%.2f GB"), value / (1024.0 * 1024.0 * 1024.0));
        }
        if (bytes >= 1024ull * 1024ull) {
            return wxString::Format(U8("%.2f MB"), value / (1024.0 * 1024.0));
        }
        if (bytes >= 1024ull) {
            return wxString::Format(U8("%.2f KB"), value / 1024.0);
        }
        return wxString::Format(U8("%llu B"), static_cast<unsigned long long>(bytes));
    }

    wxButton* chooseButton_ = nullptr;
    wxChoice* modeChoice_ = nullptr;
    std::array<wxToggleButton*, std::size(kFpsOptions)> fpsButtons_ {};
    wxStaticText* fileNameText_ = nullptr;
    wxStaticText* fileSizeText_ = nullptr;
    wxStaticText* frameText_ = nullptr;
    wxStaticText* statusText_ = nullptr;
    QrCanvas* canvas_ = nullptr;
    wxTimer timer_;
    FileQrEncoder encoder_;
    int selectedFps_ = kTargetFps;
};

class FileQrApp final : public wxApp {
public:
    bool OnInit() override
    {
        if (!wxApp::OnInit()) {
            return false;
        }

        cimbar::Config::update(kDefaultMode);
        auto* frame = new MainFrame();
        frame->Maximize(true);
        frame->Show(true);
        return true;
    }
};

} // namespace

wxIMPLEMENT_APP(FileQrApp);
