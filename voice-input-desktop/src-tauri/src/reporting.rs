use crate::storage::config::{AppConfig, OpenAiReportConfig};
use crate::storage::history::{self, HistoryQuery, HistoryRecord};
use chrono::{Local, TimeZone};
use docx_rs::*;
use futures_util::StreamExt;
use reqwest::header::{ACCEPT, CONTENT_TYPE};
use serde::Serialize;
use serde_json::Value;
use std::time::Duration;
use tauri::Emitter;

const DEFAULT_OPENAI_API_URL: &str = "https://api.openai.com/v1/responses";
const MAX_REPORT_CONTEXT_CHARS: usize = 120_000;
const MAX_ERROR_BODY_CHARS: usize = 600;
const MAX_RESPONSE_PREVIEW_CHARS: usize = 800;
const A4_WIDTH: u32 = 11906;
const A4_HEIGHT: u32 = 16838;
const MARGIN_TOP: i32 = 2098;
const MARGIN_BOTTOM: i32 = 1984;
const MARGIN_LEFT: i32 = 1587;
const MARGIN_RIGHT: i32 = 1474;
const HEADER_MARGIN: i32 = 850;
const FOOTER_MARGIN: i32 = 850;
const TITLE_FONT: &str = "方正小标宋_GBK";
const BODY_FONT: &str = "方正仿宋_GBK";
const HEADING_FONT: &str = "方正黑体_GBK";
const SUBHEADING_FONT: &str = "方正楷体_GBK";
const TITLE_SIZE: usize = 44;
const HEADING_SIZE: usize = 36;
const SUBHEADING_SIZE: usize = 32;
const BODY_SIZE: usize = 32;
const FIXED_LINE_SPACING: i32 = 560;
const LIST_INDENT_LEFT: i32 = 840;
const LIST_HANGING_INDENT: i32 = 360;

#[derive(Debug, Clone, Serialize)]
pub struct GeneratedReport {
    pub period: String,
    pub start_at: i64,
    pub end_at: i64,
    pub model_name: String,
    pub record_count: usize,
    pub used_record_count: usize,
    pub content: String,
}

#[derive(Clone)]
pub struct ReportStreamHandle {
    pub app_handle: tauri::AppHandle,
    pub request_id: String,
}

#[derive(Clone, Serialize)]
struct ReportStreamDeltaPayload {
    request_id: String,
    delta: String,
}

pub async fn generate_openai_report(
    config: AppConfig,
    period: &str,
    start_at: i64,
    end_at: i64,
    stream_handle: Option<ReportStreamHandle>,
) -> Result<GeneratedReport, String> {
    let openai = config.openai;
    validate_openai_config(&openai)?;

    let mut records = history::list_records(HistoryQuery {
        start_at: Some(start_at),
        end_at: Some(end_at),
        limit: None,
        before_received_at: None,
        before_id: None,
    })
    .map_err(|e| e.to_string())?;

    if records.is_empty() {
        return Err("所选时间范围内没有历史记录，无法生成报告".to_string());
    }

    records.sort_by(|left, right| {
        left.received_at
            .cmp(&right.received_at)
            .then_with(|| left.id.cmp(&right.id))
    });

    let total_record_count = records.len();
    let (records_context, used_record_count) = build_records_context(&records);
    let prompt = build_prompt(period, start_at, end_at, total_record_count, &records_context, &openai)?;
    let api_target = resolve_api_target(&openai.api_url);

    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(120))
        .build()
        .map_err(|e| format!("创建 HTTP 客户端失败: {e}"))?;

    let response = client
        .post(&api_target.endpoint)
        .header(ACCEPT, "application/json, text/event-stream")
        .bearer_auth(openai.api_key.trim())
        .json(&build_api_payload(&api_target.protocol, &openai.model_name, &prompt))
        .send()
        .await
        .map_err(|e| format!("请求 OpenAI API 失败: {e}"))?;

    let status = response.status();
    let parsed_body = read_api_response_body(response, &api_target.protocol, stream_handle.as_ref()).await?;

    if !status.is_success() {
        return Err(format!(
            "OpenAI API 返回错误 ({}): {}",
            status.as_u16(),
            extract_error_message_from_body(&parsed_body)
        ));
    }

    let content = parsed_body
        .content
        .clone()
        .or_else(|| {
            parsed_body
                .json
                .as_ref()
                .and_then(|response_json| extract_response_text(response_json, &api_target.protocol))
        })
        .ok_or_else(|| build_unparseable_response_error(&api_target, &parsed_body))?;

    Ok(GeneratedReport {
        period: period.to_string(),
        start_at,
        end_at,
        model_name: openai.model_name,
        record_count: total_record_count,
        used_record_count,
        content,
    })
}

pub fn export_report_to_word(
    period: &str,
    title: &str,
    start_at: i64,
    end_at: i64,
    content: &str,
) -> Result<Vec<u8>, String> {
    let normalized = content.trim();
    if normalized.is_empty() {
        return Err("当前报告内容为空，无法导出 Word".to_string());
    }

    let mut doc = Docx::new()
        .page_size(A4_WIDTH, A4_HEIGHT)
        .page_margin(PageMargin {
            top: MARGIN_TOP,
            left: MARGIN_LEFT,
            bottom: MARGIN_BOTTOM,
            right: MARGIN_RIGHT,
            header: HEADER_MARGIN,
            footer: FOOTER_MARGIN,
            gutter: 0,
        })
        .default_fonts(body_fonts())
        .default_size(BODY_SIZE)
        .default_line_spacing(
            LineSpacing::new()
                .line_rule(LineSpacingType::Exact)
                .line(FIXED_LINE_SPACING),
        )
        .add_paragraph(
            Paragraph::new()
                .align(AlignmentType::Center)
                .line_spacing(exact_line_spacing())
                .add_run(
                    Run::new()
                        .add_text(format!("{title}{suffix}", suffix = report_period_suffix(period)))
                        .fonts(title_fonts())
                        .size(TITLE_SIZE),
                ),
        )
        .add_paragraph(
            Paragraph::new()
                .align(AlignmentType::Center)
                .line_spacing(exact_line_spacing())
                .add_run(
                    Run::new()
                        .add_text(format!(
                            "时间范围：{} 至 {}",
                            format_timestamp(start_at),
                            format_timestamp(end_at)
                        ))
                        .fonts(body_fonts())
                        .size(BODY_SIZE),
                ),
        )
        .add_paragraph(
            Paragraph::new()
                .align(AlignmentType::Center)
                .line_spacing(exact_line_spacing())
                .add_run(
                    Run::new()
                        .add_text(format!(
                            "导出时间：{}",
                            format_timestamp(Local::now().timestamp_millis())
                        ))
                        .fonts(body_fonts())
                        .size(BODY_SIZE),
                ),
        )
        .add_paragraph(Paragraph::new().line_spacing(exact_line_spacing()));

    for line in prepare_report_lines(period, normalized) {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            doc = doc.add_paragraph(Paragraph::new().line_spacing(exact_line_spacing()));
            continue;
        }

        doc = doc.add_paragraph(build_report_paragraph(trimmed));
    }

    let mut buffer = std::io::Cursor::new(Vec::<u8>::new());
    doc.build()
        .pack(&mut buffer)
        .map_err(|e| format!("生成 Word 文档失败: {e}"))?;
    Ok(buffer.into_inner())
}

fn validate_openai_config(config: &OpenAiReportConfig) -> Result<(), String> {
    if config.api_key.trim().is_empty() {
        return Err("请先填写 OpenAI API Key".to_string());
    }
    if config.model_name.trim().is_empty() {
        return Err("请先填写模型名称".to_string());
    }
    if config.weekly_prompt_template.trim().is_empty()
        || config.monthly_prompt_template.trim().is_empty()
    {
        return Err("周报和月报模板不能为空".to_string());
    };
    Ok(())
}

fn build_prompt(
    period: &str,
    start_at: i64,
    end_at: i64,
    record_count: usize,
    records_context: &str,
    openai: &OpenAiReportConfig,
) -> Result<String, String> {
    let (period_label, template) = match period {
        "week" => ("周报", openai.weekly_prompt_template.as_str()),
        "month" => ("月报", openai.monthly_prompt_template.as_str()),
        _ => return Err("暂不支持该报告类型".to_string()),
    };

    let replacements = [
        ("{{period_label}}", period_label),
        ("{{start_date}}", &format_timestamp(start_at)),
        ("{{end_date}}", &format_timestamp(end_at)),
        ("{{record_count}}", &record_count.to_string()),
        ("{{records}}", records_context),
    ];

    let mut prompt = template.to_string();
    for (token, value) in replacements {
        prompt = prompt.replace(token, value);
    }

    if !prompt.contains(records_context) {
        prompt.push_str("\n\n原始记录：\n");
        prompt.push_str(records_context);
    }

    Ok(prompt)
}

fn build_records_context(records: &[HistoryRecord]) -> (String, usize) {
    let mut lines = Vec::new();
    let mut total_len = 0usize;
    let mut used_count = 0usize;

    for (index, record) in records.iter().enumerate() {
        let line = format!(
            "[{idx}] 发送={sent_at} | 接收={received_at} | 来源={from_device} | 通道={via} | 模式={mode}\n内容：{content}\n",
            idx = index + 1,
            sent_at = format_timestamp(record.sent_at),
            received_at = format_timestamp(record.received_at),
            from_device = sanitize_inline_text(&record.from_device_name, &record.from_device_id),
            via = sanitize_inline_text(&record.via, "unknown"),
            mode = sanitize_inline_text(&record.delivery_mode, "live"),
            content = sanitize_multiline_text(&record.content),
        );

        if total_len + line.len() > MAX_REPORT_CONTEXT_CHARS {
            let omitted = records.len().saturating_sub(used_count);
            lines.push(format!("... 已省略 {} 条较早记录，避免上下文过长。", omitted));
            break;
        }

        total_len += line.len();
        lines.push(line);
        used_count += 1;
    }

    (lines.join("\n"), used_count)
}

fn resolve_api_target(api_url: &str) -> ApiTarget {
    let trimmed = api_url.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return ApiTarget {
            endpoint: DEFAULT_OPENAI_API_URL.to_string(),
            protocol: ApiProtocol::Responses,
        };
    }
    if trimmed.contains("/chat/completions") {
        return ApiTarget {
            endpoint: trimmed.to_string(),
            protocol: ApiProtocol::ChatCompletions,
        };
    }
    if trimmed.contains("/responses") {
        return ApiTarget {
            endpoint: trimmed.to_string(),
            protocol: ApiProtocol::Responses,
        };
    }
    if trimmed.contains("/compatible-mode/") || trimmed.contains("dashscope") {
        return ApiTarget {
            endpoint: format!("{trimmed}/chat/completions"),
            protocol: ApiProtocol::ChatCompletions,
        };
    }
    if trimmed.ends_with("/v1") {
        return ApiTarget {
            endpoint: format!("{trimmed}/responses"),
            protocol: ApiProtocol::Responses,
        };
    }
    if trimmed.contains("/v1/") {
        return ApiTarget {
            endpoint: trimmed.to_string(),
            protocol: ApiProtocol::Responses,
        };
    }
    ApiTarget {
        endpoint: format!("{trimmed}/v1/responses"),
        protocol: ApiProtocol::Responses,
    }
}

fn extract_response_text(response_json: &Value, protocol: &ApiProtocol) -> Option<String> {
    match protocol {
        ApiProtocol::Responses => extract_responses_text(response_json)
            .or_else(|| extract_chat_completions_text(response_json)),
        ApiProtocol::ChatCompletions => extract_chat_completions_text(response_json)
            .or_else(|| extract_responses_text(response_json)),
    }
}

fn extract_error_message(response_json: &Value) -> String {
    response_json
        .get("error")
        .and_then(|value| value.get("message"))
        .and_then(Value::as_str)
        .map(|value| value.to_string())
        .or_else(|| {
            let raw = response_json.to_string();
            let trimmed = raw.trim();
            if trimmed.is_empty() {
                None
            } else {
                Some(trimmed.chars().take(MAX_ERROR_BODY_CHARS).collect())
            }
        })
        .unwrap_or_else(|| "未知错误".to_string())
}

fn extract_error_message_from_body(parsed_body: &ParsedApiBody) -> String {
    if let Some(message) = parsed_body.error_message.as_deref() {
        let trimmed = message.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }

    if let Some(response_json) = parsed_body.json.as_ref() {
        return extract_error_message(response_json);
    }

    if !parsed_body.body_preview.is_empty() {
        return parsed_body.body_preview.clone();
    }

    "未知错误".to_string()
}

fn build_unparseable_response_error(api_target: &ApiTarget, parsed_body: &ParsedApiBody) -> String {
    let mut message = format!(
        "OpenAI API 未返回可解析的报告内容。当前请求地址: {}",
        api_target.endpoint
    );

    if api_target.endpoint.contains("dashscope.aliyuncs.com")
        || api_target.endpoint.contains("/compatible-mode/")
    {
        message.push_str(
            "。如使用阿里云百炼兼容模式，Base URL 可填写 https://dashscope.aliyuncs.com/compatible-mode/v1，程序会自动请求 /chat/completions",
        );
    } else {
        message.push_str(&format!(
            "。当前按 {} 协议解析",
            api_protocol_name(&api_target.protocol)
        ));
    }

    if parsed_body.transport == ResponseBodyTransport::Sse {
        message.push_str("。当前响应为 SSE 流式输出");
    }

    let preview = if let Some(response_json) = parsed_body.json.as_ref() {
        response_body_preview(response_json)
    } else {
        parsed_body.body_preview.clone()
    };
    if !preview.is_empty() {
        message.push_str("。响应片段: ");
        message.push_str(&preview);
    }

    message
}

fn format_timestamp(timestamp: i64) -> String {
    Local
        .timestamp_millis_opt(timestamp)
        .single()
        .map(|value| value.format("%Y-%m-%d %H:%M:%S").to_string())
        .unwrap_or_else(|| timestamp.to_string())
}

fn api_protocol_name(protocol: &ApiProtocol) -> &'static str {
    match protocol {
        ApiProtocol::Responses => "responses",
        ApiProtocol::ChatCompletions => "chat/completions",
    }
}

fn response_body_preview(response_json: &Value) -> String {
    let raw = response_json.to_string();
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        String::new()
    } else {
        trimmed.chars().take(MAX_RESPONSE_PREVIEW_CHARS).collect()
    }
}

fn prepare_report_lines(period: &str, content: &str) -> Vec<String> {
    let mut lines = Vec::new();
    let mut skipping_leading_meta = true;

    for raw_line in content.lines() {
        let line = raw_line.trim_end();
        let trimmed = line.trim();

        if skipping_leading_meta {
            if trimmed.is_empty() {
                continue;
            }
            if should_skip_export_metadata_line(period, trimmed) {
                continue;
            }
            skipping_leading_meta = false;
        }

        lines.push(line.to_string());
    }

    lines
}

fn should_skip_export_metadata_line(period: &str, line: &str) -> bool {
    if line.starts_with("时间范围：") || line.starts_with("导出时间：") {
        return true;
    }

    let normalized = strip_markdown_heading_prefix(line)
        .map(|(_, text)| text)
        .unwrap_or(line)
        .trim();

    match period {
        "week" => normalized.starts_with("周报") || normalized.starts_with("工作周报"),
        "month" => normalized.starts_with("月报") || normalized.starts_with("工作月报"),
        _ => false,
    }
}

fn build_report_paragraph(line: &str) -> Paragraph {
    let parsed = parse_report_line(line);
    let mut paragraph = match parsed.style {
        ReportLineStyle::Heading => Paragraph::new()
            .align(AlignmentType::Left)
            .line_spacing(exact_line_spacing()),
        ReportLineStyle::SubHeading => Paragraph::new()
            .align(AlignmentType::Left)
            .line_spacing(exact_line_spacing()),
        ReportLineStyle::Body => Paragraph::new()
            .align(AlignmentType::Both)
            .line_spacing(exact_line_spacing())
            .first_line_chars(200),
        ReportLineStyle::BulletItem | ReportLineStyle::NumberedItem => Paragraph::new()
            .align(AlignmentType::Both)
            .line_spacing(exact_line_spacing())
            .indent(
                Some(LIST_INDENT_LEFT),
                Some(SpecialIndentType::Hanging(LIST_HANGING_INDENT)),
                None,
                None,
            ),
    };

    if let Some(marker) = parsed.marker.as_deref() {
        paragraph = paragraph.add_run(
            Run::new()
                .add_text(format!("{marker} "))
                .fonts(body_fonts())
                .size(BODY_SIZE),
        );
    }

    add_markdown_runs(paragraph, &parsed.text, parsed.style)
}

fn parse_report_line(line: &str) -> ParsedReportLine {
    let trimmed = line.trim();

    if let Some((level, text)) = strip_markdown_heading_prefix(trimmed) {
        return ParsedReportLine {
            style: if level <= 3 {
                ReportLineStyle::Heading
            } else {
                ReportLineStyle::SubHeading
            },
            marker: None,
            text: text.trim().to_string(),
        };
    }

    if let Some((marker, text)) = strip_unordered_list_marker(trimmed) {
        return ParsedReportLine {
            style: ReportLineStyle::BulletItem,
            marker: Some(marker),
            text,
        };
    }

    if let Some((marker, text)) = strip_ordered_list_marker(trimmed) {
        return ParsedReportLine {
            style: ReportLineStyle::NumberedItem,
            marker: Some(marker),
            text,
        };
    }

    let heading_prefixes = [
        "一、", "二、", "三、", "四、", "五、", "六、", "七、", "八、", "九、", "十、",
    ];
    if heading_prefixes.iter().any(|prefix| trimmed.starts_with(prefix)) {
        return ParsedReportLine {
            style: ReportLineStyle::Heading,
            marker: None,
            text: trimmed.to_string(),
        };
    }

    let subheading_prefixes = [
        "（一）", "（二）", "（三）", "（四）", "（五）", "（六）", "（七）", "（八）",
        "(一)", "(二)", "(三)", "(四)", "(五)", "(六)", "(七)", "(八)",
    ];
    if subheading_prefixes.iter().any(|prefix| trimmed.starts_with(prefix)) {
        return ParsedReportLine {
            style: ReportLineStyle::SubHeading,
            marker: None,
            text: trimmed.to_string(),
        };
    }

    ParsedReportLine {
        style: ReportLineStyle::Body,
        marker: None,
        text: trimmed.to_string(),
    }
}

fn strip_markdown_heading_prefix(line: &str) -> Option<(usize, &str)> {
    let level = line.chars().take_while(|ch| *ch == '#').count();
    if level == 0 {
        return None;
    }

    let remainder = line[level..].trim_start();
    if remainder.is_empty() {
        None
    } else {
        Some((level, remainder))
    }
}

fn strip_unordered_list_marker(line: &str) -> Option<(String, String)> {
    for marker in ["- ", "* ", "+ "] {
        if let Some(rest) = line.strip_prefix(marker) {
            return Some(("•".to_string(), rest.trim().to_string()));
        }
    }
    None
}

fn strip_ordered_list_marker(line: &str) -> Option<(String, String)> {
    let mut digits_end = 0usize;
    for (index, ch) in line.char_indices() {
        if ch.is_ascii_digit() {
            digits_end = index + ch.len_utf8();
        } else {
            break;
        }
    }

    if digits_end == 0 {
        return None;
    }

    let number = &line[..digits_end];
    let remainder = &line[digits_end..];
    for separator in [".", "、", ")", "）"] {
        if let Some(rest) = remainder.strip_prefix(separator) {
            return Some((
                format!("{number}{separator}"),
                rest.trim().to_string(),
            ));
        }
    }

    None
}

fn add_markdown_runs(
    mut paragraph: Paragraph,
    text: &str,
    style: ReportLineStyle,
) -> Paragraph {
    let segments: Vec<&str> = text.split("**").collect();
    let has_markdown = segments.len() > 1;

    for (index, segment) in segments.iter().enumerate() {
        if segment.is_empty() {
            continue;
        }

        let mut run = build_report_run(segment, style);
        if has_markdown && index % 2 == 1 {
            run = run.bold();
        }
        paragraph = paragraph.add_run(run);
    }

    if !has_markdown && text.is_empty() {
        paragraph = paragraph.add_run(build_report_run("", style));
    }

    paragraph
}

fn build_report_run(text: &str, style: ReportLineStyle) -> Run {
    match style {
        ReportLineStyle::Heading => Run::new()
            .add_text(text)
            .fonts(heading_fonts())
            .size(HEADING_SIZE)
            .bold(),
        ReportLineStyle::SubHeading => Run::new()
            .add_text(text)
            .fonts(subheading_fonts())
            .size(SUBHEADING_SIZE)
            .bold(),
        ReportLineStyle::Body | ReportLineStyle::BulletItem | ReportLineStyle::NumberedItem => {
            Run::new()
                .add_text(text)
                .fonts(body_fonts())
                .size(BODY_SIZE)
        }
    }
}

async fn read_api_response_body(
    response: reqwest::Response,
    protocol: &ApiProtocol,
    stream_handle: Option<&ReportStreamHandle>,
) -> Result<ParsedApiBody, String> {
    let content_type = response
        .headers()
        .get(CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("")
        .to_string();

    if content_type.contains("text/event-stream") {
        consume_sse_response_body(response, protocol, stream_handle).await
    } else {
        let response_text = response
            .text()
            .await
            .map_err(|e| format!("读取 OpenAI API 响应失败: {e}"))?;
        Ok(parse_api_response_body(&response_text, &content_type, protocol))
    }
}

fn parse_api_response_body(
    response_text: &str,
    content_type: &str,
    protocol: &ApiProtocol,
) -> ParsedApiBody {
    let trimmed = response_text.trim();
    if trimmed.is_empty() {
        return ParsedApiBody {
            json: None,
            content: None,
            error_message: None,
            body_preview: String::new(),
            transport: ResponseBodyTransport::Text,
        };
    }

    if let Ok(response_json) = serde_json::from_str::<Value>(trimmed) {
        let error_message = extract_json_error_message(&response_json);
        let content = extract_response_text(&response_json, protocol);
        return ParsedApiBody {
            json: Some(response_json),
            content,
            error_message,
            body_preview: response_text_preview(trimmed),
            transport: ResponseBodyTransport::Json,
        };
    }

    if looks_like_sse(content_type, trimmed) {
        return parse_sse_response_body(trimmed, protocol);
    }

    ParsedApiBody {
        json: None,
        content: None,
        error_message: None,
        body_preview: response_text_preview(trimmed),
        transport: ResponseBodyTransport::Text,
    }
}

async fn consume_sse_response_body(
    response: reqwest::Response,
    protocol: &ApiProtocol,
    stream_handle: Option<&ReportStreamHandle>,
) -> Result<ParsedApiBody, String> {
    let mut stream = response.bytes_stream();
    let mut raw_response = String::new();
    let mut normalized_buffer = String::new();
    let mut state = StreamingParseState::default();

    while let Some(chunk) = stream.next().await {
        let bytes = chunk.map_err(|e| format!("读取 OpenAI API 流式响应失败: {e}"))?;
        let chunk_text = String::from_utf8_lossy(&bytes);
        raw_response.push_str(&chunk_text);
        normalized_buffer.push_str(&chunk_text.replace("\r\n", "\n"));

        while let Some((block, rest)) = take_next_sse_block(&normalized_buffer) {
            process_sse_block(&block, protocol, stream_handle, &mut state);
            normalized_buffer = rest;
        }
    }

    if !normalized_buffer.trim().is_empty() {
        process_sse_block(&normalized_buffer, protocol, stream_handle, &mut state);
    }

    let content = state
        .final_json
        .as_ref()
        .and_then(|response_json| extract_response_text(response_json, protocol))
        .or_else(|| {
            if state.content_chunks.is_empty() {
                None
            } else {
                Some(state.content_chunks.join(""))
            }
        });

    if let (Some(content), Some(stream_handle)) = (&content, stream_handle) {
        if state.content_chunks.is_empty() && !content.is_empty() {
            emit_report_stream_delta(stream_handle, content);
        }
    }

    Ok(ParsedApiBody {
        json: state.final_json,
        content,
        error_message: state.error_message,
        body_preview: response_text_preview(&raw_response),
        transport: ResponseBodyTransport::Sse,
    })
}

fn take_next_sse_block(buffer: &str) -> Option<(String, String)> {
    buffer.find("\n\n").map(|index| {
        let block = buffer[..index].to_string();
        let rest = buffer[index + 2..].to_string();
        (block, rest)
    })
}

fn process_sse_block(
    block: &str,
    protocol: &ApiProtocol,
    stream_handle: Option<&ReportStreamHandle>,
    state: &mut StreamingParseState,
) {
    let mut data_lines = Vec::new();
    for line in block.lines() {
        if let Some(data) = line.strip_prefix("data:") {
            data_lines.push(data.trim_start().to_string());
        }
    }

    if data_lines.is_empty() {
        return;
    }

    let data = data_lines.join("\n");
    process_sse_data_chunk(&data, protocol, stream_handle, state);
}

fn process_sse_data_chunk(
    data: &str,
    protocol: &ApiProtocol,
    stream_handle: Option<&ReportStreamHandle>,
    state: &mut StreamingParseState,
) {
    let trimmed = data.trim();
    if trimmed.is_empty() || trimmed == "[DONE]" {
        return;
    }

    let Ok(event_json) = serde_json::from_str::<Value>(trimmed) else {
        return;
    };

    if state.error_message.is_none() {
        state.error_message = extract_json_error_message(&event_json);
    }

    if let Some(response) = event_json.get("response") {
        state.final_json = Some(response.clone());
    } else if state.final_json.is_none() && is_complete_response_object(&event_json, protocol) {
        state.final_json = Some(event_json.clone());
    }

    if let Some(delta) = extract_sse_delta_text(&event_json, protocol) {
        if !delta.is_empty() {
            if let Some(stream_handle) = stream_handle {
                emit_report_stream_delta(stream_handle, &delta);
            }
            state.content_chunks.push(delta);
        }
    }
}

fn emit_report_stream_delta(stream_handle: &ReportStreamHandle, delta: &str) {
    if delta.is_empty() {
        return;
    }

    stream_handle
        .app_handle
        .emit(
            "openai_report_delta",
            ReportStreamDeltaPayload {
                request_id: stream_handle.request_id.clone(),
                delta: delta.to_string(),
            },
        )
        .unwrap_or(());
}

fn looks_like_sse(content_type: &str, response_text: &str) -> bool {
    content_type.contains("text/event-stream")
        || response_text.contains("\ndata:")
        || response_text.starts_with("data:")
}

fn parse_sse_response_body(response_text: &str, protocol: &ApiProtocol) -> ParsedApiBody {
    let mut final_json: Option<Value> = None;
    let mut content_chunks = Vec::new();
    let mut error_message: Option<String> = None;

    for data in extract_sse_data_chunks(response_text) {
        let trimmed = data.trim();
        if trimmed.is_empty() || trimmed == "[DONE]" {
            continue;
        }

        let Ok(event_json) = serde_json::from_str::<Value>(trimmed) else {
            continue;
        };

        if error_message.is_none() {
            error_message = extract_json_error_message(&event_json);
        }

        if let Some(response) = event_json.get("response") {
            final_json = Some(response.clone());
        } else if final_json.is_none() && is_complete_response_object(&event_json, protocol) {
            final_json = Some(event_json.clone());
        }

        if let Some(chunk) = extract_sse_delta_text(&event_json, protocol) {
            if !chunk.is_empty() {
                content_chunks.push(chunk);
            }
        }
    }

    let content = final_json
        .as_ref()
        .and_then(|response_json| extract_response_text(response_json, protocol))
        .or_else(|| {
            if content_chunks.is_empty() {
                None
            } else {
                Some(content_chunks.join(""))
            }
        });

    ParsedApiBody {
        json: final_json,
        content,
        error_message,
        body_preview: response_text_preview(response_text),
        transport: ResponseBodyTransport::Sse,
    }
}

fn extract_sse_data_chunks(response_text: &str) -> Vec<String> {
    let normalized = response_text.replace("\r\n", "\n");
    let mut chunks = Vec::new();

    for block in normalized.split("\n\n") {
        let mut data_lines = Vec::new();
        for line in block.lines() {
            if let Some(data) = line.strip_prefix("data:") {
                data_lines.push(data.trim_start().to_string());
            }
        }
        if !data_lines.is_empty() {
            chunks.push(data_lines.join("\n"));
        }
    }

    chunks
}

fn extract_sse_delta_text(event_json: &Value, protocol: &ApiProtocol) -> Option<String> {
    match protocol {
        ApiProtocol::Responses => extract_responses_sse_delta_text(event_json)
            .or_else(|| extract_chat_completions_sse_delta_text(event_json)),
        ApiProtocol::ChatCompletions => extract_chat_completions_sse_delta_text(event_json)
            .or_else(|| extract_responses_sse_delta_text(event_json)),
    }
}

fn extract_responses_sse_delta_text(event_json: &Value) -> Option<String> {
    let event_type = event_json.get("type").and_then(Value::as_str).unwrap_or("");
    if event_type.contains("output_text.delta") || event_type.ends_with(".delta") {
        return event_json
            .get("delta")
            .and_then(Value::as_str)
            .map(ToString::to_string)
            .filter(|value| !value.is_empty());
    }

    None
}

fn extract_chat_completions_sse_delta_text(event_json: &Value) -> Option<String> {
    let choices = event_json.get("choices")?.as_array()?;
    let mut chunks = Vec::new();

    for choice in choices {
        let delta = choice.get("delta")?;

        if let Some(content) = delta.get("content").and_then(Value::as_str) {
            if !content.is_empty() {
                chunks.push(content.to_string());
            }
            continue;
        }

        if let Some(parts) = delta.get("content").and_then(Value::as_array) {
            for part in parts {
                if let Some(text) = part.get("text").and_then(Value::as_str) {
                    if !text.is_empty() {
                        chunks.push(text.to_string());
                    }
                } else if let Some(text) = part.as_str() {
                    if !text.is_empty() {
                        chunks.push(text.to_string());
                    }
                }
            }
        }
    }

    if chunks.is_empty() {
        None
    } else {
        Some(chunks.join(""))
    }
}

fn extract_json_error_message(response_json: &Value) -> Option<String> {
    if response_json.get("error").is_some() {
        Some(extract_error_message(response_json))
    } else {
        None
    }
}

fn is_complete_response_object(response_json: &Value, protocol: &ApiProtocol) -> bool {
    extract_response_text(response_json, protocol).is_some()
}

fn response_text_preview(response_text: &str) -> String {
    response_text
        .trim()
        .chars()
        .take(MAX_RESPONSE_PREVIEW_CHARS)
        .collect()
}

fn exact_line_spacing() -> LineSpacing {
    LineSpacing::new()
        .line_rule(LineSpacingType::Exact)
        .line(FIXED_LINE_SPACING)
}

fn body_fonts() -> RunFonts {
    RunFonts::new()
        .ascii("Times New Roman")
        .hi_ansi("Times New Roman")
        .east_asia(BODY_FONT)
        .cs(BODY_FONT)
}

fn title_fonts() -> RunFonts {
    RunFonts::new()
        .ascii("Times New Roman")
        .hi_ansi("Times New Roman")
        .east_asia(TITLE_FONT)
        .cs(TITLE_FONT)
}

fn heading_fonts() -> RunFonts {
    RunFonts::new()
        .ascii("Times New Roman")
        .hi_ansi("Times New Roman")
        .east_asia(HEADING_FONT)
        .cs(HEADING_FONT)
}

fn subheading_fonts() -> RunFonts {
    RunFonts::new()
        .ascii("Times New Roman")
        .hi_ansi("Times New Roman")
        .east_asia(SUBHEADING_FONT)
        .cs(SUBHEADING_FONT)
}

fn report_period_suffix(period: &str) -> &'static str {
    match period {
        "week" => "",
        "month" => "",
        _ => "",
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ReportLineStyle {
    Heading,
    SubHeading,
    Body,
    BulletItem,
    NumberedItem,
}

struct ParsedReportLine {
    style: ReportLineStyle,
    marker: Option<String>,
    text: String,
}

fn sanitize_inline_text(value: &str, fallback: &str) -> String {
    let trimmed = value.trim();
    let source = if trimmed.is_empty() { fallback } else { trimmed };
    source.replace('\n', " ").replace('\r', " ")
}

fn sanitize_multiline_text(value: &str) -> String {
    value
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .collect::<Vec<_>>()
        .join(" / ")
}

fn build_api_payload(protocol: &ApiProtocol, model_name: &str, prompt: &str) -> Value {
    match protocol {
        ApiProtocol::Responses => serde_json::json!({
            "model": model_name,
            "stream": true,
            "instructions": "你是一名中文工作总结助手。只能基于用户提供的记录总结，禁止虚构未出现的事项；如信息不足，要直接说明记录未体现。",
            "input": prompt,
        }),
        ApiProtocol::ChatCompletions => serde_json::json!({
            "model": model_name,
            "stream": true,
            "messages": [
                {
                    "role": "system",
                    "content": "你是一名中文工作总结助手。只能基于用户提供的记录总结，禁止虚构未出现的事项；如信息不足，要直接说明记录未体现。"
                },
                {
                    "role": "user",
                    "content": prompt
                }
            ]
        }),
    }
}

fn extract_responses_text(response_json: &Value) -> Option<String> {
    if let Some(output_text) = response_json.get("output_text").and_then(Value::as_str) {
        let trimmed = output_text.trim();
        if !trimmed.is_empty() {
            return Some(trimmed.to_string());
        }
    }

    let output = response_json.get("output")?.as_array()?;
    let mut chunks = Vec::new();

    for item in output {
        let content = item.get("content").and_then(Value::as_array);
        if let Some(content_items) = content {
            for content_item in content_items {
                let text = content_item
                    .get("text")
                    .and_then(Value::as_str)
                    .map(str::trim)
                    .filter(|value| !value.is_empty());
                if let Some(text) = text {
                    chunks.push(text.to_string());
                }
            }
        }
    }

    if chunks.is_empty() {
        None
    } else {
        Some(chunks.join("\n\n"))
    }
}

fn extract_chat_completions_text(response_json: &Value) -> Option<String> {
    let choices = response_json.get("choices")?.as_array()?;
    let first = choices.first()?;
    let message = first.get("message")?;
    let content = message.get("content")?;

    if let Some(text) = content.as_str() {
        let trimmed = text.trim();
        if !trimmed.is_empty() {
            return Some(trimmed.to_string());
        }
    }

    if let Some(parts) = content.as_array() {
        let mut chunks = Vec::new();
        for part in parts {
            if let Some(text) = part.get("text").and_then(Value::as_str) {
                let trimmed = text.trim();
                if !trimmed.is_empty() {
                    chunks.push(trimmed.to_string());
                }
            }
        }
        if !chunks.is_empty() {
            return Some(chunks.join("\n\n"));
        }
    }

    None
}

struct ApiTarget {
    endpoint: String,
    protocol: ApiProtocol,
}

struct ParsedApiBody {
    json: Option<Value>,
    content: Option<String>,
    error_message: Option<String>,
    body_preview: String,
    transport: ResponseBodyTransport,
}

#[derive(Default)]
struct StreamingParseState {
    final_json: Option<Value>,
    content_chunks: Vec<String>,
    error_message: Option<String>,
}

enum ApiProtocol {
    Responses,
    ChatCompletions,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ResponseBodyTransport {
    Json,
    Sse,
    Text,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prepare_report_lines_skips_export_metadata_and_duplicate_title() {
        let lines = prepare_report_lines(
            "week",
            "时间范围：2026-03-30 00:00:00 至 2026-04-02 22:52:55\n导出时间：2026-04-02 22:53:25\n\n### 周报（2026-03-30 至 2026-04-02）\n\n#### 总览\n本周完成了修复。\n",
        );

        assert_eq!(lines, vec!["#### 总览".to_string(), "本周完成了修复。".to_string()]);
    }

    #[test]
    fn parse_report_line_supports_markdown_headings_and_lists() {
        let heading = parse_report_line("#### 总览");
        assert_eq!(heading.style, ReportLineStyle::SubHeading);
        assert_eq!(heading.text, "总览");

        let ordered = parse_report_line("1. **发送情况跟进**");
        assert_eq!(ordered.style, ReportLineStyle::NumberedItem);
        assert_eq!(ordered.marker.as_deref(), Some("1."));
        assert_eq!(ordered.text, "**发送情况跟进**");

        let bullet = parse_report_line("- 继续关注和处理相关发送情况。");
        assert_eq!(bullet.style, ReportLineStyle::BulletItem);
        assert_eq!(bullet.marker.as_deref(), Some("•"));
        assert_eq!(bullet.text, "继续关注和处理相关发送情况。");
    }

    #[test]
    fn parse_sse_response_body_supports_chat_completion_chunks() {
        let parsed = parse_sse_response_body(
            "data: {\"choices\":[{\"delta\":{\"content\":\"### 周报\\n\"}}]}\n\n\
             data: {\"choices\":[{\"delta\":{\"content\":\"- 已完成修复\"}}]}\n\n\
             data: [DONE]\n\n",
            &ApiProtocol::ChatCompletions,
        );

        assert_eq!(parsed.transport, ResponseBodyTransport::Sse);
        assert_eq!(parsed.content.as_deref(), Some("### 周报\n- 已完成修复"));
    }

    #[test]
    fn parse_sse_response_body_supports_responses_delta() {
        let parsed = parse_sse_response_body(
            "event: response.output_text.delta\n\
             data: {\"type\":\"response.output_text.delta\",\"delta\":\"总览：\"}\n\n\
             event: response.output_text.delta\n\
             data: {\"type\":\"response.output_text.delta\",\"delta\":\"本周推进顺利。\"}\n\n\
             data: [DONE]\n\n",
            &ApiProtocol::Responses,
        );

        assert_eq!(parsed.transport, ResponseBodyTransport::Sse);
        assert_eq!(parsed.content.as_deref(), Some("总览：本周推进顺利。"));
    }
}
