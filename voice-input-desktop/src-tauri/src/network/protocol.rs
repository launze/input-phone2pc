use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum Message {
    #[serde(rename = "DISCOVER")]
    Discover {
        device_id: String,
        device_name: String,
        version: String,
    },
    #[serde(rename = "DISCOVER_RESPONSE")]
    DiscoverResponse {
        device_id: String,
        device_name: String,
        ip: String,
        port: u16,
        version: String,
    },
    #[serde(rename = "PAIR_REQUEST")]
    PairRequest {
        device_id: String,
        device_name: String,
        pin: String,
    },
    #[serde(rename = "PAIR_RESPONSE")]
    PairResponse {
        success: bool,
        message: String,
    },
    #[serde(rename = "TEXT_INPUT")]
    TextInput {
        text: String,
        timestamp: i64,
    },
    #[serde(rename = "INPUT_ACK")]
    InputAck {
        success: bool,
        timestamp: i64,
    },
    #[serde(rename = "HEARTBEAT")]
    Heartbeat {
        timestamp: i64,
    },
    #[serde(rename = "ENCRYPTION_KEY_EXCHANGE")]
    EncryptionKeyExchange {
        device_id: String,
        public_key: String,
    },
    #[serde(rename = "ENCRYPTED_MESSAGE")]
    EncryptedMessage {
        from_device_id: String,
        to_device_id: String,
        ciphertext: String,
        nonce: String,
    },
}

impl Message {
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    pub fn from_json(json: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(json)
    }
}
