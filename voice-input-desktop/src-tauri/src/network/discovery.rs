use crate::network::protocol::Message;
use std::error::Error;
use tauri::AppHandle;
use tokio::net::UdpSocket;

const UDP_PORT: u16 = 58888;
const TCP_PORT: u16 = 58889;

pub async fn start_discovery_service(_app_handle: AppHandle) -> Result<(), Box<dyn Error>> {
    let socket = UdpSocket::bind(format!("0.0.0.0:{}", UDP_PORT)).await?;
    socket.set_broadcast(true)?;

    println!("UDP 发现服务已启动，监听端口: {}", UDP_PORT);

    let mut buf = vec![0u8; 1024];

    loop {
        match socket.recv_from(&mut buf).await {
            Ok((len, addr)) => {
                let data = &buf[..len];
                if let Ok(text) = std::str::from_utf8(data) {
                    if let Ok(message) = Message::from_json(text) {
                        if let Message::Discover {
                            device_id,
                            device_name,
                            version: _,
                        } = message
                        {
                            println!("收到发现请求: {} ({})", device_name, device_id);

                            // 获取本机设备信息（使用持久化的配置）
                            let app_config = crate::storage::config::AppConfig::load();
                            let local_device_id = app_config.device_id;
                            let local_device_name = app_config.device_name;

                            // 构造响应消息
                            let response = Message::DiscoverResponse {
                                device_id: local_device_id,
                                device_name: local_device_name,
                                ip: get_local_ip().unwrap_or_else(|| "0.0.0.0".to_string()),
                                port: TCP_PORT,
                                version: "1.0.0".to_string(),
                            };

                            // 发送响应
                            if let Ok(json) = response.to_json() {
                                let _ = socket.send_to(json.as_bytes(), addr).await;
                            }
                        }
                    }
                }
            }
            Err(e) => {
                eprintln!("UDP 接收错误: {}", e);
            }
        }
    }
}

pub fn get_local_ip() -> Option<String> {
    use std::net::UdpSocket as StdUdpSocket;

    // 尝试连接到外部地址以获取本地 IP
    let socket = StdUdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|addr| addr.ip().to_string())
}
