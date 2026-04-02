# 内存泄漏修复 - 实施指南

## 已完成的改动

### 1. ✅ 改进的 device_manager.rs
已完全重写，包含以下改进：

**新增功能**:
- `ManagerConfig` 结构体 - 配置设备超时、清理间隔、最大设备数
- `register_device()` 返回 `Result` - 检查设备数量上限
- `cleanup_stale_devices()` - 清理超过超时时间的设备
- `cleanup_orphaned_pairings()` - 清理孤立的配对关系
- `get_stats()` - 获取设备管理器统计信息
- 单元测试 - 测试注册、注销、配对、设备限制

**关键改进**:
```rust
// 防止无限增长
pub fn register_device(...) -> Result<(), String> {
    if self.devices.len() >= self.config.max_devices {
        return Err("Server full".to_string());
    }
    // ...
}

// 定期清理过期设备
pub fn cleanup_stale_devices(&self) -> usize {
    let now = chrono::Utc::now().timestamp();
    let timeout = self.config.device_timeout_secs;
    // 移除超时设备...
}
```

---

## 需要手动完成的改动

### 2. ⚠️ 更新 main.rs 中的 register_device 调用

**位置**: 第 200-220 行左右

**原代码**:
```rust
device_manager.register_device(
    dev_id.clone(),
    device_name.clone(),
    device_type.clone(),
    session_id.clone(),
    tx.clone(),
);
*device_id = Some(dev_id.clone());
```

**新代码**:
```rust
match device_manager.register_device(
    dev_id.clone(),
    device_name.clone(),
    device_type.clone(),
    session_id.clone(),
    tx.clone(),
) {
    Ok(()) => {
        *device_id = Some(dev_id.clone());
        let response = ServerMessage::ServerRegisterResponse {
            success: true,
            session_id,
            message: "registered".to_string(),
        };
        tx.send(Message::Text(response.to_json()?))?;
        info!("device registered: {} ({}) [{}]", device_name, dev_id, device_type);
    }
    Err(e) => {
        warn!("❌ 设备注册失败: {} - {}", dev_id, e);
        let response = ServerMessage::ServerRegisterResponse {
            success: false,
            session_id,
            message: format!("Registration failed: {}", e),
        };
        tx.send(Message::Text(response.to_json()?))?;
        return Ok(());
    }
}
```

---

### 3. ⚠️ 更新 main.rs 中的 heartbeat_checker 函数

**位置**: 第 530+ 行

**原代码**:
```rust
async fn heartbeat_checker(device_manager: Arc<DeviceManager>, pairing_db: Arc<PairingDb>) {
    let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(30));

    loop {
        interval.tick().await;
        let devices = device_manager.get_online_devices(None);
        let now = chrono::Utc::now().timestamp();
        for device in devices {
            if now - device.last_seen > 120 {
                notify_paired_devices_offline(&device.device_id, &device_manager, &pairing_db);
                info!("device heartbeat timeout: {}", device.device_id);
            }
        }
    }
}
```

**新代码**:
```rust
async fn heartbeat_checker(device_manager: Arc<DeviceManager>, _pairing_db: Arc<PairingDb>) {
    let cleanup_interval = std::time::Duration::from_secs(30);
    let mut interval = tokio::time::interval(cleanup_interval);

    loop {
        interval.tick().await;

        // 1. 清理过期设备
        let stale_count = device_manager.cleanup_stale_devices();

        // 2. 清理孤立的配对关系
        let orphaned_count = device_manager.cleanup_orphaned_pairings();

        // 3. 获取统计信息
        let stats = device_manager.get_stats();

        // 4. 记录统计信息
        if stale_count > 0 || orphaned_count > 0 {
            info!(
                "📊 清理统计 - 过期设备: {}, 孤立配对: {}, 当前在线: {}/{}, 连接: {}",
                stale_count,
                orphaned_count,
                stats.total_devices,
                stats.max_devices,
                stats.active_connections
            );
        }

        // 5. 如果设备数量接近上限，发出警告
        let usage_percent = (stats.total_devices as f64 / stats.max_devices as f64) * 100.0;
        if usage_percent > 80.0 {
            warn!(
                "⚠️ 设备容量告急: {:.1}% ({}/{})",
                usage_percent, stats.total_devices, stats.max_devices
            );
        }
    }
}
```

---

### 4. ⚠️ 可选：移除 notify_paired_devices_offline 函数

由于新的清理机制已经处理了设备离线通知，可以考虑移除或简化这个函数。

**原函数**:
```rust
fn notify_paired_devices_offline(device_id: &str, device_manager: &DeviceManager, pairing_db: &PairingDb) {
    let paired_ids = pairing_db.get_paired_devices(device_id).unwrap_or_default();
    let device_info = device_manager.unregister_device(device_id);

    if let Some(device) = device_info {
        for (paired_id, _) in &paired_ids {
            let offline_msg = ServerMessage::PairedDeviceOffline {
                device_id: device.device_id.clone(),
                device_name: device.device_name.clone(),
                device_type: device.device_type.clone(),
            };
            if let Ok(json) = offline_msg.to_json() {
                let _ = device_manager.send_to_device(paired_id, Message::Text(json));
            }
        }
    }
}
```

**新函数** (可选):
```rust
fn notify_paired_devices_offline(device_id: &str, device_manager: &DeviceManager, _pairing_db: &PairingDb) {
    // 获取配对设备列表
    let paired_ids = device_manager.get_paired_device_ids(device_id);
    
    // 注销设备
    if let Some(device) = device_manager.unregister_device(device_id) {
        // 通知配对设备
        for paired_id in paired_ids {
            let offline_msg = ServerMessage::PairedDeviceOffline {
                device_id: device.device_id.clone(),
                device_name: device.device_name.clone(),
                device_type: device.device_type.clone(),
            };
            if let Ok(json) = offline_msg.to_json() {
                let _ = device_manager.send_to_device(&paired_id, Message::Text(json));
            }
        }
    }
}
```

---

## 测试改动

### 编译测试
```bash
cd voice-input-server
cargo build --release
```

### 运行单元测试
```bash
cargo test device_manager
```

### 运行服务器
```bash
cargo run --release
```

观察日志输出，应该看到类似的清理日志：
```
📊 清理统计 - 过期设备: 2, 孤立配对: 1, 当前在线: 98/10000, 连接: 98
```

---

## 改进效果

### 内存泄漏防护
✅ **设备数量限制** - 防止无限增长（默认10000个）
✅ **自动清理** - 每30秒清理一次过期设备
✅ **孤立关系清理** - 移除不存在的设备的配对记录
✅ **容量监控** - 当接近上限时发出警告

### 监控和调试
✅ **统计信息** - 实时获取设备、连接、配对数量
✅ **详细日志** - 清理操作的详细日志记录
✅ **容量告警** - 当使用率超过80%时警告

### 性能改进
✅ **内存使用稳定** - 不再无限增长
✅ **连接管理** - 及时释放断开连接的资源
✅ **数据库一致性** - 清理孤立的配对关系

---

## 配置调整

如需调整清理参数，在 `main.rs` 中修改：

```rust
let device_manager = Arc::new(DeviceManager::with_config(
    device_manager::ManagerConfig {
        device_timeout_secs: 120,      // 设备超时时间（秒）
        cleanup_interval_secs: 30,     // 清理间隔（秒）
        max_devices: 10000,            // 最大设备数量
    }
));
```

---

## 总结

这个改进方案解决了以下问题：

1. **设备列表无限增长** → 添加了最大设备数量限制
2. **断开连接的设备未清理** → 自动清理超时设备
3. **孤立配对关系** → 定期清理不存在的设备的配对记录
4. **缺少监控** → 添加了详细的统计信息和日志
5. **容量管理** → 当接近上限时发出警告

预计可以将内存使用量从无限增长降低到稳定的水平。
