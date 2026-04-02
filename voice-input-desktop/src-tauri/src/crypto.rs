use aes_gcm::{aead::Aead, Aes256Gcm, KeyInit, Nonce};
use hex::{decode, encode};
use rand::Rng;

pub const KEY_SIZE: usize = 32; // 256 bits
pub const NONCE_SIZE: usize = 12; // 96 bits

#[derive(Debug, Clone)]
pub struct EncryptionKey(pub [u8; KEY_SIZE]);

impl EncryptionKey {
    pub fn generate() -> Self {
        let mut key = [0u8; KEY_SIZE];
        rand::thread_rng().fill(&mut key);
        Self(key)
    }

    pub fn from_hex(hex_str: &str) -> Result<Self, anyhow::Error> {
        let bytes = decode(hex_str)?;
        if bytes.len() != KEY_SIZE {
            return Err(anyhow::anyhow!("Invalid key length"));
        }
        let mut key = [0u8; KEY_SIZE];
        key.copy_from_slice(&bytes);
        Ok(Self(key))
    }

    pub fn to_hex(&self) -> String {
        encode(&self.0)
    }
}

pub fn encrypt(data: &[u8], key: &EncryptionKey) -> Result<(String, String), anyhow::Error> {
    let cipher = Aes256Gcm::new_from_slice(&key.0)
        .map_err(|e| anyhow::anyhow!("Failed to create cipher: {:?}", e))?;
    let mut nonce = [0u8; NONCE_SIZE];
    rand::thread_rng().fill(&mut nonce);

    let ciphertext = cipher
        .encrypt(Nonce::from_slice(&nonce), data)
        .map_err(|e| anyhow::anyhow!("Encryption failed: {:?}", e))?;

    Ok((encode(&ciphertext), encode(&nonce)))
}

pub fn decrypt(
    ciphertext_hex: &str,
    nonce_hex: &str,
    key: &EncryptionKey,
) -> Result<Vec<u8>, anyhow::Error> {
    let cipher = Aes256Gcm::new_from_slice(&key.0)
        .map_err(|e| anyhow::anyhow!("Failed to create cipher: {:?}", e))?;
    let ciphertext = decode(ciphertext_hex)?;
    let nonce = decode(nonce_hex)?;

    if nonce.len() != NONCE_SIZE {
        return Err(anyhow::anyhow!("Invalid nonce length"));
    }

    let plaintext = cipher
        .decrypt(Nonce::from_slice(&nonce), &ciphertext[..])
        .map_err(|e| anyhow::anyhow!("Decryption failed: {:?}", e))?;
    Ok(plaintext)
}

pub fn encrypt_string(text: &str, key: &EncryptionKey) -> Result<(String, String), anyhow::Error> {
    let data = text.as_bytes();
    encrypt(data, key)
}

pub fn decrypt_string(
    ciphertext_hex: &str,
    nonce_hex: &str,
    key: &EncryptionKey,
) -> Result<String, anyhow::Error> {
    let data = decrypt(ciphertext_hex, nonce_hex, key)?;
    String::from_utf8(data).map_err(|e| anyhow::anyhow!("Invalid UTF-8: {:?}", e))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encryption_decryption() {
        let key = EncryptionKey::generate();
        let plaintext = "Hello, encrypted world!";

        let (ciphertext, nonce) = encrypt_string(plaintext, &key).unwrap();
        let decrypted = decrypt_string(&ciphertext, &nonce, &key).unwrap();

        assert_eq!(plaintext, decrypted);
    }

    #[test]
    fn test_key_serialization() {
        let key1 = EncryptionKey::generate();
        let hex = key1.to_hex();
        let key2 = EncryptionKey::from_hex(&hex).unwrap();

        assert_eq!(key1.0, key2.0);
    }
}
