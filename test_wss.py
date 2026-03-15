#!/usr/bin/env python3
"""
测试 WSS 连接到 nas.smarthome2020.top:7070
"""

import asyncio
import websockets
import ssl
import sys

async def test_wss_connection():
    uri = "wss://nas.smarthome2020.top:7070"
    
    print(f"正在连接到: {uri}")
    
    # 创建 SSL 上下文，信任自签名证书
    ssl_context = ssl.create_default_context()
    ssl_context.check_hostname = False
    ssl_context.verify_mode = ssl.CERT_NONE
    
    try:
        async with websockets.connect(uri, ssl=ssl_context) as websocket:
            print("✅ WSS 连接成功!")
            print(f"服务器地址: {websocket.remote_address}")
            
            # 发送测试消息
            test_message = '{"type": "ping", "data": "test"}'
            await websocket.send(test_message)
            print(f"已发送: {test_message}")
            
            # 等待响应
            response = await websocket.recv()
            print(f"收到响应: {response}")
            
    except websockets.exceptions.InvalidStatusCode as e:
        print(f"❌ 连接失败: HTTP {e.status_code}")
        print(f"状态码: {e.status_code}")
        print(f"原因: {e.reason}")
        
        if e.status_code == 426:
            print("\n提示: 服务器要求使用 WSS (WebSocket Secure)")
            print("请确保使用 wss:// 协议而不是 ws://")
            
    except ConnectionRefusedError:
        print("❌ 连接被拒绝")
        print("请检查:")
        print("1. 服务器是否正在运行")
        print("2. 端口 7070 是否开放")
        print("3. 防火墙设置")
        
    except ssl.SSLError as e:
        print(f"❌ SSL 错误: {e}")
        print("请检查:")
        print("1. 服务器证书是否有效")
        print("2. 证书是否过期")
        
    except Exception as e:
        print(f"❌ 连接失败: {type(e).__name__}: {e}")

if __name__ == "__main__":
    print("="*50)
    print("WSS 连接测试工具")
    print("="*50)
    
    try:
        asyncio.run(test_wss_connection())
    except KeyboardInterrupt:
        print("\n\n测试已取消")
        sys.exit(0)
