use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ServerMessage {
    // 服务器注册
    #[serde(rename = "SERVER_REGISTER")]
    ServerRegister {
        device_id: String,
        device_name: String,
        device_type: String, // "android" | "desktop"
        version: String,
    },

    #[serde(rename = "SERVER_REGISTER_RESPONSE")]
    ServerRegisterResponse {
        success: bool,
        session_id: String,
        message: String,
    },

    // 设备列表
    #[serde(rename = "DEVICE_LIST_REQUEST")]
    DeviceListRequest {
        device_type: Option<String>, // "desktop" | "android" | "all"
    },

    #[serde(rename = "DEVICE_LIST_RESPONSE")]
    DeviceListResponse { devices: Vec<DeviceInfo> },

    // 服务器配对
    #[serde(rename = "SERVER_PAIR_REQUEST")]
    ServerPairRequest {
        from_device_id: String,
        from_device_name: String,
        to_device_id: String,
        pin: String,
    },

    #[serde(rename = "SERVER_PAIR_RESPONSE")]
    ServerPairResponse {
        success: bool,
        from_device_id: String,
        from_device_name: String,
        to_device_id: String,
        to_device_name: String,
        message: String,
    },

    // 消息中转
    #[serde(rename = "RELAY_MESSAGE")]
    RelayMessage {
        from_device_id: String,
        to_device_id: String,
        payload: serde_json::Value,
        #[serde(skip_serializing_if = "Option::is_none")]
        message_id: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        from_device_name: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        stored_at: Option<i64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        delivery_mode: Option<String>, // "live" | "offline_sync"
    },

    #[serde(rename = "RELAY_STORED")]
    RelayStored {
        message_id: String,
        to_device_id: String,
        stored_at: i64,
        queued: bool,
    },

    // 通知转发
    #[serde(rename = "NOTIFICATION_FORWARD")]
    NotificationForward {
        from_device_id: String,
        to_device_id: String,
        notification: NotificationData,
    },

    // 心跳
    #[serde(rename = "HEARTBEAT")]
    Heartbeat { timestamp: i64 },

    // 错误消息
    #[serde(rename = "ERROR")]
    Error { code: String, message: String },

    // 配对设备上线通知
    #[serde(rename = "PAIRED_DEVICE_ONLINE")]
    PairedDeviceOnline {
        device_id: String,
        device_name: String,
        device_type: String,
    },

    // 配对设备离线通知
    #[serde(rename = "PAIRED_DEVICE_OFFLINE")]
    PairedDeviceOffline {
        device_id: String,
        device_name: String,
        device_type: String,
    },

    // 取消配对
    #[serde(rename = "UNPAIR_REQUEST")]
    UnpairRequest {
        from_device_id: String,
        to_device_id: String,
    },

    // 取消配对通知（服务器发给另一方）
    #[serde(rename = "UNPAIR_NOTIFY")]
    UnpairNotify {
        device_id: String,
        device_name: String,
    },

    // 图片消息
    #[serde(rename = "IMAGE_MESSAGE")]
    ImageMessage {
        from_device_id: String,
        to_device_id: String,
        image_data: String,   // base64 encoded
        image_format: String, // "jpeg", "png", etc.
        image_size: u32,      // original size in bytes
        timestamp: i64,
    },

    // 加密相关
    #[serde(rename = "ENCRYPTION_KEY_EXCHANGE")]
    EncryptionKeyExchange {
        device_id: String,
        public_key: String, // 用于密钥协商的公钥
    },

    #[serde(rename = "ENCRYPTED_MESSAGE")]
    EncryptedMessage {
        from_device_id: String,
        to_device_id: String,
        ciphertext: String,
        nonce: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceInfo {
    pub device_id: String,
    pub device_name: String,
    pub device_type: String,
    pub online: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationData {
    pub app_name: String,
    pub app_package: String,
    pub title: String,
    pub text: String,
    pub timestamp: i64,
    pub icon: Option<String>, // base64 encoded
}

impl ServerMessage {
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    pub fn from_json(json: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(json)
    }
}
